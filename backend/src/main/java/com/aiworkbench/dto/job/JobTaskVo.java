package com.aiworkbench.dto.job;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 定时任务项(供「任务面板」展示)。 */
@Data
@Builder
public class JobTaskVo {
    private Long id;
    private Long userId;
    private String name;
    private String prompt;
    /** cron 表达式;null=手动一次性 */
    private String cron;
    private String agent;
    /** 1=非交互 --auto */
    private boolean autoApprove;
    private boolean enabled;
    /** 任务变量(键→值):运行时替换 prompt 中的 ${key} 占位符 */
    private Map<String, String> variables;
    /** pending / queued / running / success / failed / timeout / cancelled */
    private String status;
    private String statusText;
    private LocalDateTime nextRunAt;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 最近一次运行结果摘要 */
    private String lastResult;
    private String lastError;
    private Long runningSeconds;
}