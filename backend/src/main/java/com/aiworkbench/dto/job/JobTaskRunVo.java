package com.aiworkbench.dto.job;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时任务单次运行历史项(供「任务结果」展示)。 */
@Data
@Builder
public class JobTaskRunVo {
    private Long id;
    private Long jobId;
    /** running/success/failed/timeout/cancelled */
    private String status;
    private String statusText;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer exitCode;
    private Integer changedFiles;
    /** 本次运行结果摘要 */
    private String result;
    private String error;
    /** 本次运行的日志文件路径(工作区内) */
    private String runLogPath;
    private Long runningSeconds;
}
