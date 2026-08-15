package com.aiworkbench.service.job;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.JobTask;
import com.aiworkbench.mapper.JobTaskMapper;
import com.aiworkbench.service.WorkspaceService;
import com.aiworkbench.service.notification.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务队列调度器(核心排队逻辑)。
 * <p>
 * 容器资源有限 → 全局并发上限 {@code opencode.job-max-concurrent}(默认 1):
 * <ol>
 *   <li>每 {@code job-sweep-ms}(默认 5s)扫描:对状态 running 但容器已不存在的做故障回收;
 *   <li>计算当前实际运行数,未达上限时按排队时间(FIFO)把 queued 任务提交到线程池执行;
 *   <li>执行单元在独立快照工作区跑 {@code opencode run},结束后回写变更、生成执行报告、
 *       向消息中心发一条任务消息、更新状态;
 *   <li>cron 任务结束后计算下次触发回到 pending;一次性任务进入终态。
 * </ol>
 * 线程池大小 = 并发上限,配合内存计数器双重约束,避免超发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueueDispatcher {

    private final JobTaskMapper jobTaskMapper;
    private final JobTaskService jobTaskService;
    private final JobWorkspaceService jobWorkspaceService;
    private final JobContainerManager jobContainerManager;
    private final OpencodeProperties props;
    private final WorkspaceService workspaceService;
    private final NotificationService notificationService;

    /** 内存运行计数(配合线程池大小双重限制) */
    private final AtomicInteger runningCount = new AtomicInteger();
    private ThreadPoolTaskExecutor executor;

    @PostConstruct
    public void init() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, props.getJobMaxConcurrent()));
        executor.setMaxPoolSize(Math.max(1, props.getJobMaxConcurrent()));
        executor.setQueueCapacity(0); // 同步队列:超限直接拒绝,由调度器控制入队
        executor.setThreadNamePrefix("aiwb-job-");
        executor.initialize();
        // 清理历史残留任务容器(应用重启时)
        try {
            jobContainerManager.cleanupAllStale();
        } catch (Exception e) {
            log.warn("stale job container cleanup failed: {}", e.toString());
        }
        // 重启恢复:把 running 任务重置回 queued(容器已清,重新排队执行)
        jobTaskMapper.update(new JobTask(), new LambdaUpdateWrapper<JobTask>()
                .eq(JobTask::getStatus, JobTaskService.RUNNING)
                .set(JobTask::getStatus, JobTaskService.QUEUED)
                .set(JobTask::getQueuedAt, LocalDateTime.now())
                .set(JobTask::getStartedAt, null)
                .set(JobTask::getContainerName, null));
        int n = runningCount.get();
        log.info("job queue dispatcher ready (maxConcurrent={}, recovered running={})",
                props.getJobMaxConcurrent(), n);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** 定时扫描:故障回收 + 并发调度。 */
    @Scheduled(fixedDelayString = "${opencode.job-sweep-ms:5000}")
    public void sweep() {
        reconcileCrash();
        dispatch();
    }

    /** 故障回收:状态 running 但容器已不存在(进程被杀/容器被删) → 标记 failed。 */
    private void reconcileCrash() {
        List<JobTask> running = jobTaskMapper.selectList(new LambdaQueryWrapper<JobTask>()
                .eq(JobTask::getStatus, JobTaskService.RUNNING));
        for (JobTask job : running) {
            if (job.getContainerName() == null) {
                continue;
            }
            if (!jobContainerManager.isRunning(job.getId())) {
                log.warn("job {} container {} gone, marking failed", job.getId(), job.getContainerName());
                finish(job, JobTaskService.FAILED, null, "任务容器异常退出(可能被中断),请查看日志或重新触发");
            }
        }
    }

    /** 并发调度:FIFO 取 queued 任务,未达上限即提交执行。 */
    private void dispatch() {
        int max = Math.max(1, props.getJobMaxConcurrent());
        while (runningCount.get() < max) {
            // CAS:单次取一个 queued 任务,置 running,防止多线程重复领取
            List<JobTask> one = jobTaskMapper.selectList(new LambdaQueryWrapper<JobTask>()
                    .eq(JobTask::getStatus, JobTaskService.QUEUED)
                    .orderByAsc(JobTask::getQueuedAt)
                    .orderByAsc(JobTask::getId)
                    .last("limit 1"));
            if (one.isEmpty()) {
                return;
            }
            JobTask job = one.get(0);
            boolean claimed = jobTaskMapper.update(new JobTask(), new LambdaUpdateWrapper<JobTask>()
                    .eq(JobTask::getId, job.getId())
                    .eq(JobTask::getStatus, JobTaskService.QUEUED)
                    .set(JobTask::getStatus, JobTaskService.RUNNING)
                    .set(JobTask::getStartedAt, LocalDateTime.now())) > 0;
            if (!claimed) {
                continue; // 已被并发领取,重取
            }
            int n = runningCount.incrementAndGet();
            try {
                executor.execute(() -> execute(job));
            } catch (Exception e) {
                runningCount.decrementAndGet();
                log.error("submit job {} to executor failed: {}", job.getId(), e.toString());
                finish(job, JobTaskService.FAILED, null, "调度器提交失败: " + e.getMessage());
            }
            if (n >= max) {
                return;
            }
        }
    }

    /** 执行单元:快照 → 跑任务 → 回写变更 → 注入会话 → 终态/重排。 */
    private void execute(JobTask job) {
        Long userId = job.getUserId();
        Long jobId = job.getId();
        Path jobDir = null;
        // 运行历史:本次执行记录(running 起步,结束时写终态)
        Long runId = null;
        try {
            // 确保 uid/账户/目录就绪(幂等):任务容器以 uid 运行,须先有可写的 HOME 与工作区
            workspaceService.ensureUserWorkspace(userId);
            runId = jobTaskService.startRun(userId, jobId);
            jobDir = jobWorkspaceService.prepare(userId, jobId);
            String logFile = logPath(userId, jobId, runId);
            jobTaskMapper.update(new JobTask(), new LambdaUpdateWrapper<JobTask>()
                    .eq(JobTask::getId, jobId)
                    .set(JobTask::getContainerName, jobContainerManager.containerName(jobId))
                    .set(JobTask::getRunJobDir, jobDir.toString()));

            int uid = props.uidOf(userId);
            // 运行前基线
            var baseline = jobWorkspaceService.baseline(jobDir);
            JobContainerManager.RunResult result = jobContainerManager.run(
                    userId, jobId, uid, jobDir,
                    job.getPrompt(), job.getAgent(),
                    Integer.valueOf(1).equals(job.getAutoApprove()),
                    logFile);
            jobTaskService.updateRunContainer(runId, jobContainerManager.containerName(jobId));

            // 回写变更
            List<String> changed = jobWorkspaceService.collectChanges(userId, jobDir, baseline);
            log.info("job {} writeback {} changed file(s) to real workspace", jobId, changed.size());

            boolean success = result.exitCode() == 0;
            String summary = summarizeResult(result, changed.size());
            String finalStatus = success ? JobTaskService.SUCCESS : JobTaskService.FAILED;
            String err = success ? null : "退出码 " + result.exitCode() + (changed.isEmpty() ? "" : ",已回写 " + changed.size() + " 个文件");
            jobTaskService.finishRun(runId, finalStatus, result.exitCode(),
                    changed.size(), summary, err, logFile);
            finish(job, finalStatus, summary, err);
            // 生成任务执行报告(结果产物) + 发消息中心通知(不再注入会话)
            try {
                String reportRel = generateReport(userId, jobId, runId, job, success, result.exitCode(),
                        changed.size(), summary, err);
                notifyTaskDone(userId, jobId, runId, job.getName(), success, reportRel, summary);
            } catch (Exception e) {
                log.warn("job {} report/notify failed: {}", jobId, e.toString());
            }
        } catch (Exception e) {
            log.error("job {} execute failed: {}", job.getId(), e.toString(), e);
            if (runId != null) {
                jobTaskService.finishRun(runId, JobTaskService.FAILED, null, null,
                        null, "任务执行异常: " + e.getMessage(), null);
            }
            finish(job, JobTaskService.FAILED, null, "任务执行异常: " + e.getMessage());
        } finally {
            runningCount.decrementAndGet();
            if (jobDir != null) {
                try {
                    jobWorkspaceService.cleanup(userId, jobId);
                } catch (Exception e) {
                    log.debug("job cleanup failed: {}", e.toString());
                }
            }
        }
    }

    /** 终态或 cron 重排:终态记 finished_at;cron 任务回到 pending 并计算下次触发。 */
    private void finish(JobTask job, String status, String result, String error) {
        LambdaUpdateWrapper<JobTask> uw = new LambdaUpdateWrapper<JobTask>()
                .eq(JobTask::getId, job.getId());
        if (job.getCron() != null && !job.getCron().isBlank()
                && (SUCCESS_RETRYABLE(status))) {
            // cron 任务:本轮结束,回到 pending 等下次触发
            LocalDateTime next = jobTaskService.nextRun(job.getCron(), LocalDateTime.now());
            jobTaskMapper.update(new JobTask(), uw
                    .set(JobTask::getStatus, JobTaskService.PENDING)
                    .set(JobTask::getNextRunAt, next)
                    .set(JobTask::getQueuedAt, null)
                    .set(JobTask::getContainerName, null)
                    .set(JobTask::getLastResult, result)
                    .set(JobTask::getLastError, error));
            log.info("job {} finished {} (cron), next run at {}", job.getId(), status, next);
        } else {
            jobTaskMapper.update(new JobTask(), uw
                    .set(JobTask::getStatus, status)
                    .set(JobTask::getFinishedAt, LocalDateTime.now())
                    .set(JobTask::getContainerName, null)
                    .set(JobTask::getLastResult, result)
                    .set(JobTask::getLastError, error));
            log.info("job {} finished status={}", job.getId(), status);
        }
    }

    private boolean SUCCESS_RETRYABLE(String status) {
        return JobTaskService.SUCCESS.equals(status)
                || JobTaskService.FAILED.equals(status)
                || JobTaskService.TIMEOUT.equals(status);
    }

    /**
     * 生成任务执行报告(结果产物):写一份 markdown 到工作区
     * {@code jobs/<jobId>/runs/<runId>/report.md},记录执行情况。返回相对工作区的路径。
     */
    private String generateReport(Long userId, Long jobId, Long runId, JobTask job,
                                  boolean success, int exitCode, int changedCount,
                                  String summary, String error) {
        LocalDateTime now = LocalDateTime.now();
        String statusText = success ? "成功 ✅" : "失败 ❌";
        String rel = "jobs/" + jobId + "/runs/" + runId + "/report.md";
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务执行报告\n\n");
        sb.append("- **任务名称**: ").append(job.getName()).append("\n");
        sb.append("- **执行状态**: ").append(statusText).append("\n");
        sb.append("- **执行时间**: ").append(fmt(now)).append("\n");
        sb.append("- **退出码**: ").append(exitCode).append("\n");
        sb.append("- **回写文件数**: ").append(changedCount).append("\n");
        if (job.getCron() != null && !job.getCron().isBlank()) {
            sb.append("- **定时表达式**: `").append(job.getCron()).append("`\n");
        }
        sb.append("- **任务提示词**: ").append(singleLine(job.getPrompt())).append("\n\n");
        sb.append("## 结果摘要\n\n").append(summary == null ? "(无)" : summary).append("\n\n");
        if (error != null && !error.isBlank()) {
            sb.append("## 错误信息\n\n").append(error).append("\n\n");
        }
        sb.append("## 日志\n\n完整日志见同目录 `run.log`。\n");
        workspaceService.writeFile(userId, rel, sb.toString());
        log.info("job {} report generated: {}", jobId, rel);
        return rel;
    }

    /** 任务完成后向消息中心发一条消息,link 指向报告文件。 */
    private void notifyTaskDone(Long userId, Long jobId, Long runId, String jobName,
                                boolean success, String reportRel, String summary) {
        String title = (success ? "✅ 定时任务「" : "❌ 定时任务「") + jobName + "」执行完成";
        String content = (success ? "执行成功" : "执行失败")
                + ",退出码见报告。报告已生成:工作区 " + reportRel;
        if (summary != null && !summary.isBlank()) {
            content += "\n\n" + clip(summary, 300);
        }
        notificationService.notify(userId, "task", title, content, reportRel);
        log.info("job {} done notification sent to user {}", jobId, userId);
    }

    private String summarizeResult(JobContainerManager.RunResult r, int changed) {
        String tail = r.logTail();
        String clipped = clip(tail, 400);
        StringBuilder sb = new StringBuilder();
        sb.append("退出码 ").append(r.exitCode());
        sb.append(",回写 ").append(changed).append(" 个文件");
        if (!clipped.isBlank()) {
            sb.append("\n").append(clipped);
        }
        return sb.toString();
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private String singleLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').trim();
    }

    private String fmt(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 任务运行日志路径:每次运行独立,便于按运行历史查看。 */
    private String logPath(Long userId, Long jobId, Long runId) {
        return workspaceService.resolveWritePath(userId, "jobs/" + jobId + "/runs/" + runId + "/run.log").toString();
    }
}