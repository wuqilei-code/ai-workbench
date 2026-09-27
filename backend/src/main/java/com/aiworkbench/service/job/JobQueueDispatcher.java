package com.aiworkbench.service.job;

import com.aiworkbench.config.AgentJobProperties;
import com.aiworkbench.entity.JobTask;
import com.aiworkbench.mapper.JobTaskMapper;
import com.aiworkbench.service.DbLockService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务队列调度器(核心排队逻辑)。
 * <p>
 * agent-service 与在线对话共享执行后端 → 全局并发上限 {@code agent-jobs.max-concurrent}:
 * <ol>
 *   <li>每 {@code agent-jobs.sweep-ms} 扫描:对状态 running 但执行器已超时/中断的做故障回收;</li>
 *   <li>计算当前实际运行数,未达上限时按排队时间(FIFO)把 queued 任务提交到线程池执行;</li>
 *   <li>执行单元由 {@link JobAgentExecutor} 经 agent-service SSE 在用户工作区完成,
 *       结束后生成执行报告、向消息中心发一条任务消息、更新状态;</li>
 *   <li>cron 任务结束后计算下次触发回到 pending;一次性任务进入终态。</li>
 * </ol>
 * 线程池大小 = 并发上限,配合内存计数器双重约束,避免超发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueueDispatcher {

    private final JobTaskMapper jobTaskMapper;
    private final JobTaskService jobTaskService;
    private final JobAgentExecutor jobAgentExecutor;
    private final AgentJobProperties props;
    private final WorkspaceService workspaceService;
    private final NotificationService notificationService;
    private final DbLockService dbLock;
    private final JobPauseState pauseState;

    /** 内存运行计数(配合线程池大小双重限制) */
    private final AtomicInteger runningCount = new AtomicInteger();
    private ThreadPoolTaskExecutor executor;

    @PostConstruct
    public void init() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, props.getMaxConcurrent()));
        executor.setMaxPoolSize(Math.max(1, props.getMaxConcurrent()));
        executor.setQueueCapacity(0); // 同步队列:超限直接拒绝,由调度器控制入队
        executor.setThreadNamePrefix("aiwb-job-");
        executor.initialize();
        // 重启恢复:把 running 任务重置回 queued(执行器无持久运行状态,重新排队执行)
        jobTaskMapper.update(new JobTask(), new LambdaUpdateWrapper<JobTask>()
                .eq(JobTask::getStatus, JobTaskService.RUNNING)
                .set(JobTask::getStatus, JobTaskService.QUEUED)
                .set(JobTask::getQueuedAt, LocalDateTime.now())
                .set(JobTask::getStartedAt, null)
                .set(JobTask::getContainerName, null));
        log.info("job queue dispatcher ready (maxConcurrent={})", props.getMaxConcurrent());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** 定时扫描:并发调度。多副本时仅一个实例执行(避免各自的内存并发计数叠加超发)。 */
    @Scheduled(fixedDelayString = "${agent-jobs.sweep-ms:5000}")
    public void sweep() {
        if (pauseState.isPaused()) return; // 全局暂停:不派发新任务(运行中的照常完成)
        if (!dbLock.tryLock("job-dispatch-sweep", 30)) return;
        dispatch();
    }

    /** 并发调度:FIFO 取 queued 任务,未达上限即提交执行。 */
    private void dispatch() {
        int max = Math.max(1, props.getMaxConcurrent());
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

    /** 执行单元:agent-service 直接执行 → 报告 → 终态/重排。 */
    private void execute(JobTask job) {
        Long userId = job.getUserId();
        Long jobId = job.getId();
        Long runId = null;
        try {
            // 确保 uid/账户/工作区就绪(幂等):agent 在用户工作区执行,须先有目录
            workspaceService.ensureUserWorkspace(userId);
            runId = jobTaskService.startRun(userId, jobId);
            String logFile = logPath(userId, jobId, runId);

            JobAgentExecutor.RunResult result = jobAgentExecutor.run(
                    userId, jobId, runId, jobTaskService.resolvePrompt(job), logFile);

            boolean success = result.exitCode() == 0;
            boolean timedOut = result.exitCode() == 124;
            String summary = summarizeResult(result);
            String finalStatus = timedOut ? JobTaskService.TIMEOUT
                    : (success ? JobTaskService.SUCCESS : JobTaskService.FAILED);
            String err = success || timedOut ? null : "执行失败(退出码 " + result.exitCode() + "),日志见运行详情";
            if (timedOut) {
                err = "任务超过 " + props.getTimeoutMinutes() + " 分钟超时,已强制中断";
            }
            jobTaskService.finishRun(runId, finalStatus, result.exitCode(), 0, summary, err, logFile);
            finish(job, finalStatus, summary, err);
            // 生成任务执行报告(结果产物) + 发消息中心通知
            try {
                String reportRel = generateReport(userId, jobId, runId, job, success,
                        result.exitCode(), summary, err);
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
        }
    }

    /** 终态或重试/重排:
     *  - 失败且未达重试上限:立即重新入队重试(retryCount+1);
     *  - cron 任务:本轮结束回到 pending 等下次触发;
     *  - 其余:进入终态。成功/达上限后重置 retryCount。 */
    private void finish(JobTask job, String status, String result, String error) {
        boolean failure = JobTaskService.FAILED.equals(status) || JobTaskService.TIMEOUT.equals(status);
        int retry = job.getRetryCount() == null ? 0 : job.getRetryCount();
        int maxRetries = Math.max(0, props.getMaxRetries());

        if (failure && retry < maxRetries) {
            jobTaskMapper.update(new JobTask(), new LambdaUpdateWrapper<JobTask>()
                    .eq(JobTask::getId, job.getId())
                    .set(JobTask::getStatus, JobTaskService.QUEUED)
                    .set(JobTask::getQueuedAt, LocalDateTime.now())
                    .set(JobTask::getRetryCount, retry + 1)
                    .set(JobTask::getContainerName, null)
                    .set(JobTask::getLastResult, result)
                    .set(JobTask::getLastError, error));
            log.info("job {} run {} -> retry {}/{}", job.getId(), status, retry + 1, maxRetries);
            return;
        }

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
                    .set(JobTask::getRetryCount, 0)
                    .set(JobTask::getLastResult, result)
                    .set(JobTask::getLastError, error));
            log.info("job {} finished {} (cron), next run at {}", job.getId(), status, next);
            if (failure) notifyWebhook(job, status, error);
        } else {
            jobTaskMapper.update(new JobTask(), uw
                    .set(JobTask::getStatus, status)
                    .set(JobTask::getFinishedAt, LocalDateTime.now())
                    .set(JobTask::getContainerName, null)
                    .set(JobTask::getRetryCount, failure ? retry : 0)
                    .set(JobTask::getLastResult, result)
                    .set(JobTask::getLastError, error));
            log.info("job {} finished status={}", job.getId(), status);
            if (failure) notifyWebhook(job, status, error);
        }
    }

    /** 可选:任务最终失败时 POST 一条 JSON 到配置的 Webhook(失败静默,不影响主流程)。 */
    private void notifyWebhook(JobTask job, String status, String error) {
        String url = props.getWebhookUrl();
        if (url == null || url.isBlank()) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "job_failed");
            payload.put("jobId", job.getId());
            payload.put("jobName", job.getName());
            payload.put("userId", job.getUserId());
            payload.put("status", status);
            payload.put("error", error == null ? "" : error);
            payload.put("at", LocalDateTime.now().toString());
            org.springframework.web.reactive.function.client.WebClient.create(url)
                    .post()
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(8));
        } catch (Exception e) {
            log.warn("job {} webhook notify failed: {}", job.getId(), e.toString());
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
                                  boolean success, int exitCode,
                                  String summary, String error) {
        String statusText = success ? "成功 ✅" : "失败 ❌";
        String rel = "jobs/" + jobId + "/runs/" + runId + "/report.md";
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务执行报告\n\n");
        sb.append("- **任务名称**: ").append(job.getName()).append("\n");
        sb.append("- **执行状态**: ").append(statusText).append("\n");
        sb.append("- **执行时间**: ").append(LocalDateTime.now().format(fmt)).append("\n");
        sb.append("- **退出码**: ").append(exitCode).append("\n");
        if (job.getCron() != null && !job.getCron().isBlank()) {
            sb.append("- **定时表达式**: `").append(job.getCron()).append("`\n");
        }
        sb.append("- **任务提示词**: ").append(singleLine(jobTaskService.resolvePrompt(job))).append("\n");
        var vars = jobTaskService.parseVars(job.getVars());
        if (!vars.isEmpty()) {
            sb.append("- **任务变量**: ");
            vars.forEach((k, v) -> sb.append("`").append(k).append("=").append(v).append("` "));
            sb.append("\n");
        }
        sb.append("\n");
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
                + ",报告已生成:工作区 " + reportRel;
        if (summary != null && !summary.isBlank()) {
            content += "\n\n" + clip(summary, 300);
        }
        notificationService.notify(userId, "task", title, content, reportRel);
        log.info("job {} done notification sent to user {}", jobId, userId);
    }

    private String summarizeResult(JobAgentExecutor.RunResult r) {
        String clipped = clip(r.logTail(), 400);
        StringBuilder sb = new StringBuilder();
        sb.append("退出码 ").append(r.exitCode());
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

    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 任务运行日志路径:每次运行独立,便于按运行历史查看。 */
    private String logPath(Long userId, Long jobId, Long runId) {
        Path p = workspaceService.resolveWritePath(userId, "jobs/" + jobId + "/runs/" + runId + "/run.log");
        return p == null ? null : p.toString();
    }
}