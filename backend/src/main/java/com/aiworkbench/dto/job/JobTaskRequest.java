package com.aiworkbench.dto.job;

import lombok.Data;

/** 创建/更新定时任务的请求。 */
@Data
public class JobTaskRequest {
    private String name;
    /** 任务提示词(必填) */
    private String prompt;
    /** cron 表达式;null/空 = 手动一次性任务 */
    private String cron;
    private String agent;
    /** 非交互运行是否 --auto */
    private Boolean autoApprove;
    private Boolean enabled;
}