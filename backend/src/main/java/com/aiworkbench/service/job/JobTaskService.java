package com.aiworkbench.service.job;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.job.JobTaskRunVo;
import com.aiworkbench.dto.job.JobTaskVo;
import com.aiworkbench.entity.JobTask;
import com.aiworkbench.entity.JobTaskRun;
import com.aiworkbench.mapper.JobTaskMapper;
import com.aiworkbench.mapper.JobTaskRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务 CRUD + 状态推进。
 * <p>
 * 状态机:pending(等待触发,cron)
 * → queued(已入队,等待并发位)→ running(容器运行中)
 * → success / failed / timeout / cancelled。
 * 一次性任务(无 cron)创建即入队;cron 任务由 {@link JobScheduler} 轮询推进 pending→queued。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobTaskService {

    private final JobTaskMapper jobTaskMapper;
    private final JobTaskRunMapper jobTaskRunMapper;

    // ---- 状态 ----

    public static final String PENDING = "pending";
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";

    // ---- CRUD ----

    public JobTask create(Long userId, String name, String prompt, String cron, String agent, boolean autoApprove) {
        validateCron(cron);
        JobTask job = new JobTask();
        job.setUserId(userId);
        job.setName(name == null || name.isBlank() ? "未命名任务" : name.trim());
        job.setPrompt(prompt == null ? "" : prompt);
        job.setCron(normCron(cron));
        job.setAgent(agent == null || agent.isBlank() ? "chat" : agent);
        job.setAutoApprove(autoApprove ? 1 : 0);
        job.setEnabled(1);
        job.setStatus(PENDING);
        if (job.getCron() == null) {
            // 一次性任务:立即排队执行
            job.setStatus(QUEUED);
            job.setQueuedAt(LocalDateTime.now());
        } else {
            job.setNextRunAt(nextRun(job.getCron(), null));
        }
        jobTaskMapper.insert(job);
        log.info("job created id={} user={} cron={} status={}", job.getId(), userId, job.getCron(), job.getStatus());
        return job;
    }

    public void update(Long userId, Long id, String name, String prompt, String cron, String agent, Boolean autoApprove, Boolean enabled) {
        JobTask job = getOwned(userId, id);
        validateCron(cron);
        if (name != null && !name.isBlank()) job.setName(name.trim());
        if (prompt != null) job.setPrompt(prompt);
        if (cron != null) job.setCron(normCron(cron));
        if (agent != null && !agent.isBlank()) job.setAgent(agent);
        if (autoApprove != null) job.setAutoApprove(autoApprove ? 1 : 0);
        if (enabled != null) job.setEnabled(enabled ? 1 : 0);
        // 修改 cron 时重排下次触发;手动任务(cron=null)与运行中任务不在此重排
        if (cron != null && !RUNNING.equals(job.getStatus()) && !QUEUED.equals(job.getStatus())) {
            job.setNextRunAt(nextRun(job.getCron(), null));
            job.setStatus(PENDING);
            job.setQueuedAt(null);
        }
        jobTaskMapper.updateById(job);
    }

    public void delete(Long userId, Long id) {
        JobTask job = getOwned(userId, id);
        if (RUNNING.equals(job.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "任务运行中,不能删除,请先取消");
        }
        jobTaskMapper.deleteById(id);
        jobTaskRunMapper.delete(new LambdaQueryWrapper<JobTaskRun>()
                .eq(JobTaskRun::getJobId, id).eq(JobTaskRun::getUserId, userId));
    }

    /** 手动触发执行:无论 cron 与否,立即入队(已排队/运行中则忽略)。 */
    public boolean trigger(Long userId, Long id) {
        JobTask job = getOwned(userId, id);
        if (QUEUED.equals(job.getStatus()) || RUNNING.equals(job.getStatus())) {
            return false;
        }
        JobTask upd = new JobTask();
        upd.setId(id);
        upd.setStatus(QUEUED);
        upd.setQueuedAt(LocalDateTime.now());
        upd.setFinishedAt(null);
        upd.setLastResult(null);
        upd.setLastError(null);
        return jobTaskMapper.update(upd, new LambdaUpdateWrapper<JobTask>()
                .eq(JobTask::getId, id)
                .ne(JobTask::getStatus, QUEUED)
                .ne(JobTask::getStatus, RUNNING)) > 0;
    }

    /** 取消排队中任务(运行中不可取消,等待其自然结束)。 */
    public boolean cancel(Long userId, Long id) {
        JobTask job = getOwned(userId, id);
        if (RUNNING.equals(job.getStatus()) || SUCCESS.equals(job.getStatus())
                || FAILED.equals(job.getStatus()) || TIMEOUT.equals(job.getStatus())
                || CANCELLED.equals(job.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "该状态的任务不能取消");
        }
        JobTask upd = new JobTask();
        upd.setId(id);
        upd.setStatus(CANCELLED);
        if (job.getCron() == null) {
            upd.setFinishedAt(LocalDateTime.now());
        } else {
            // cron 任务取消本次排队,回到 pending 等待下一触发
            upd.setStatus(PENDING);
            upd.setNextRunAt(nextRun(job.getCron(), job.getNextRunAt()));
            upd.setQueuedAt(null);
        }
        return jobTaskMapper.updateById(upd) > 0;
    }

    public JobTask getOwned(Long userId, Long id) {
        JobTask job = jobTaskMapper.selectById(id);
        if (job == null || !userId.equals(job.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return job;
    }

    public List<JobTask> listByUser(Long userId) {
        return jobTaskMapper.selectList(new LambdaQueryWrapper<JobTask>()
                .eq(JobTask::getUserId, userId)
                .orderByDesc(JobTask::getCreatedAt));
    }

    public List<JobTaskVo> toVo(List<JobTask> jobs) {
        return jobs.stream().map(this::toVo).toList();
    }

    // ---- 运行历史 ----

    /** 记录一次运行开始(running),返回 runId。 */
    public Long startRun(Long userId, Long jobId) {
        JobTaskRun run = new JobTaskRun();
        run.setJobId(jobId);
        run.setUserId(userId);
        run.setStatus(RUNNING);
        run.setStartedAt(LocalDateTime.now());
        jobTaskRunMapper.insert(run);
        return run.getId();
    }

    /** 结束一次运行:记状态、退出码、回写数、结果摘要、错误、日志路径。 */
    public void finishRun(Long runId, String status, Integer exitCode, Integer changedFiles,
                          String result, String error, String runLogPath) {
        JobTaskRun upd = new JobTaskRun();
        upd.setId(runId);
        upd.setStatus(status);
        upd.setExitCode(exitCode);
        upd.setChangedFiles(changedFiles);
        upd.setResult(result);
        upd.setError(error);
        upd.setRunLogPath(runLogPath);
        upd.setFinishedAt(LocalDateTime.now());
        jobTaskRunMapper.updateById(upd);
    }

    /** 更新运行中的 containerName。 */
    public void updateRunContainer(Long runId, String containerName) {
        JobTaskRun upd = new JobTaskRun();
        upd.setId(runId);
        upd.setContainerName(containerName);
        jobTaskRunMapper.updateById(upd);
    }

    /** 某任务的全部运行历史(新→旧)。 */
    public List<JobTaskRun> listRuns(Long userId, Long jobId) {
        getOwned(userId, jobId);
        return jobTaskRunMapper.selectList(new LambdaQueryWrapper<JobTaskRun>()
                .eq(JobTaskRun::getJobId, jobId)
                .eq(JobTaskRun::getUserId, userId)
                .orderByDesc(JobTaskRun::getId));
    }

    public List<JobTaskRunVo> toRunVo(List<JobTaskRun> runs) {
        return runs.stream().map(this::toRunVo).toList();
    }

    public JobTaskRunVo toRunVo(JobTaskRun r) {
        Long seconds = null;
        if (RUNNING.equals(r.getStatus()) && r.getStartedAt() != null) {
            seconds = Math.max(0, (System.currentTimeMillis() - toMillis(r.getStartedAt())) / 1000);
        } else if (r.getFinishedAt() != null && r.getStartedAt() != null) {
            seconds = Math.max(0, (toMillis(r.getFinishedAt()) - toMillis(r.getStartedAt())) / 1000);
        }
        return JobTaskRunVo.builder()
                .id(r.getId())
                .jobId(r.getJobId())
                .status(r.getStatus())
                .statusText(statusText(r.getStatus(), r.getError()))
                .startedAt(r.getStartedAt())
                .finishedAt(r.getFinishedAt())
                .exitCode(r.getExitCode())
                .changedFiles(r.getChangedFiles())
                .result(r.getResult())
                .error(r.getError())
                .runLogPath(r.getRunLogPath())
                .runningSeconds(seconds)
                .build();
    }

    public JobTaskVo toVo(JobTask j) {
        long now = System.currentTimeMillis();
        Long seconds = null;
        if (RUNNING.equals(j.getStatus()) && j.getStartedAt() != null) {
            seconds = Math.max(0, (now - toMillis(j.getStartedAt())) / 1000);
        } else if (j.getFinishedAt() != null && j.getStartedAt() != null) {
            seconds = Math.max(0, (toMillis(j.getFinishedAt()) - toMillis(j.getStartedAt())) / 1000);
        }
        return JobTaskVo.builder()
                .id(j.getId())
                .userId(j.getUserId())
                .name(j.getName())
                .prompt(j.getPrompt())
                .cron(j.getCron())
                .agent(j.getAgent())
                .autoApprove(Integer.valueOf(1).equals(j.getAutoApprove()))
                .enabled(Integer.valueOf(1).equals(j.getEnabled()))
                .status(j.getStatus())
                .statusText(statusText(j.getStatus(), j.getLastError()))
                .nextRunAt(j.getNextRunAt())
                .queuedAt(j.getQueuedAt())
                .startedAt(j.getStartedAt())
                .finishedAt(j.getFinishedAt())
                .lastResult(j.getLastResult())
                .lastError(j.getLastError())
                .runningSeconds(seconds)
                .build();
    }

    // ---- cron ----

    private void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return;
        }
        if (!CronExpression.isValidExpression(cron)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "cron 表达式无效: " + cron);
        }
        // 最小调度间隔 >= 1 小时:禁止秒/分钟级定时(如每秒、每分钟触发)
        CronExpression expr = CronExpression.parse(cron);
        LocalDateTime t1 = expr.next(LocalDateTime.now());
        if (t1 != null) {
            LocalDateTime t2 = expr.next(t1);
            if (t2 != null && Duration.between(t1, t2).toSeconds() < 3600) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "定时间隔不能小于 1 小时,请选择小时及以上粒度(如每N小时/每天/每周)");
            }
        }
    }

    private String normCron(String cron) {
        return (cron == null || cron.isBlank()) ? null : cron.trim();
    }

    /** 由 after 时间计算下一次触发(LocalDateTime,精确到秒)。 */
    public LocalDateTime nextRun(String cron, LocalDateTime after) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        CronExpression expr = CronExpression.parse(cron);
        LocalDateTime base = after != null ? after : LocalDateTime.now();
        return expr.next(base);
    }

    private String statusText(String status, String lastError) {
        return switch (status) {
            case PENDING -> "等待触发";
            case QUEUED -> "排队中";
            case RUNNING -> "运行中";
            case SUCCESS -> "成功";
            case FAILED -> lastError == null || lastError.isBlank() ? "失败" : "失败";
            case TIMEOUT -> "超时";
            case CANCELLED -> "已取消";
            default -> status;
        };
    }

    private long toMillis(LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}