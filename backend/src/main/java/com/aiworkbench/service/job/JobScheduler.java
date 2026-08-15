package com.aiworkbench.service.job;

import com.aiworkbench.entity.JobTask;
import com.aiworkbench.mapper.JobTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务触发:cron 任务的 {@code pending → queued} 推进。
 * <p>
 * pending 任务到 {@code next_run_at} 即入队;入队后由 {@link JobQueueDispatcher} 按并发上限调度。
 * 一次性任务(无 cron)在创建时已直接入队,不经此组件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final JobTaskMapper jobTaskMapper;
    private final JobTaskService jobTaskService;

    /** 定时扫描:每 5s(与队列调度同频),把到期的 pending 任务推入队。 */
    @Scheduled(fixedDelayString = "${opencode.job-sweep-ms:5000}")
    public void sweep() {
        List<JobTask> due = jobTaskMapper.selectList(new LambdaQueryWrapper<JobTask>()
                .eq(JobTask::getEnabled, 1)
                .eq(JobTask::getStatus, JobTaskService.PENDING)
                .isNotNull(JobTask::getNextRunAt)
                .le(JobTask::getNextRunAt, LocalDateTime.now())
                .orderByAsc(JobTask::getNextRunAt));
        for (JobTask job : due) {
            JobTask upd = new JobTask();
            upd.setStatus(JobTaskService.QUEUED);
            upd.setQueuedAt(LocalDateTime.now());
            boolean ok = jobTaskMapper.update(upd, new LambdaUpdateWrapper<JobTask>()
                    .eq(JobTask::getId, job.getId())
                    .eq(JobTask::getStatus, JobTaskService.PENDING)) > 0;
            if (ok) {
                log.info("job {} (user {}) triggered -> queued (next was {})",
                        job.getId(), job.getUserId(), job.getNextRunAt());
            }
        }
    }
}