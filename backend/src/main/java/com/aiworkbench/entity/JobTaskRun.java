package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时任务单次运行历史:每次执行一条,关联到 job_task.id。 */
@Data
@TableName("job_task_run")
public class JobTaskRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long userId;

    /** running/success/failed/timeout/cancelled */
    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Integer exitCode;

    private Integer changedFiles;

    private String result;

    private String error;

    private String containerName;

    private String runLogPath;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
