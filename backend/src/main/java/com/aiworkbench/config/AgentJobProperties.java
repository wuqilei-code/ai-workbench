package com.aiworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务(job)执行配置(deepagents 单轨:job 由 agent-service SSE 执行,无容器)。
 * <p>
 * 并发上限即 agent-service 侧的并发位数;job 超时由本进程强制中断流式调用。
 */
@Data
@ConfigurationProperties(prefix = "agent-jobs")
public class AgentJobProperties {
    /** 同时运行的任务上限(超出排队。agent-service 与在线对话共享并发,默认 1) */
    private int maxConcurrent = 1;
    /** 单任务最大运行时长(分钟),超时强制中断的保护 */
    private int timeoutMinutes = 30;
    /** 任务调度/队列扫描间隔(ms)(cron 触发与并发调度复用,默认每 5s) */
    private long sweepMs = 5000;
    /** 任务失败最大重试次数(超过后进入终态/等下次 cron;默认 2) */
    private int maxRetries = 2;
    /** 失败告警 Webhook(可选):任务最终失败时 POST JSON 到该 URL;留空则不发送 */
    private String webhookUrl = "";
    /** 全局暂停:初值来自环境变量,运行时可经管理员接口切换 */
    private boolean paused = false;
}