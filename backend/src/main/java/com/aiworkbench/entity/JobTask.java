package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时/一次性任务配置(JobScheduler/JobQueueDispatcher 排队执行)。 */
@Data
@TableName("job_task")
public class JobTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String prompt;

    /** cron 表达式;null=手动一次性任务(创建即排队) */
    private String cron;

    private String agent;

    /** 1=非交互运行 --auto(未显式拒绝的权限自动批准);0=默认拒绝 */
    private Integer autoApprove;

    private Integer enabled;

    /** pending/queued/running/success/failed/timeout/cancelled */
    private String status;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime nextRunAt;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime queuedAt;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime startedAt;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime finishedAt;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String lastResult;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String lastError;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String containerName;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String runJobDir;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}