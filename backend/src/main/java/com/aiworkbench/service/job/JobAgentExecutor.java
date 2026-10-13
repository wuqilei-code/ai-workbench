package com.aiworkbench.service.job;

import com.aiworkbench.config.AgentJobProperties;
import com.aiworkbench.service.ProviderConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定时任务执行器(deepagents 单轨):任务经 agent-service SSE 执行,不再拉起 opencode 容器。
 * <p>
 * 与在线对话同一执行后端,区别仅:
 * <ul>
 *   <li>thread_id 为合成 {@code da-job-<jobId>-<runId>},与对话线程隔离;</li>
 *   <li>无会话级事件回路,消息只进本执行器,完成即返回(成功/失败/超时);</li>
 *   <li>工具在用户真实工作区运行(与对话共享,agent 的操作即落盘,无需快照回写)。</li>
 * </ul>
 * 自动批准:agent-service 无权限/提问环节,工具直接执行,autoApprove 恒等效于开。
 */
@Slf4j
@Component
public class JobAgentExecutor {

    private final AgentJobProperties props;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ProviderConfigService providerConfigService;

    public JobAgentExecutor(
            AgentJobProperties props,
            @Value("${deepagents.base-url:http://localhost:8000}") String baseUrl,
            @Value("${app.internal-token:}") String internalToken,
            ObjectMapper objectMapper,
            ProviderConfigService providerConfigService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.providerConfigService = providerConfigService;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.webClient = WebClient.builder().baseUrl(base)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /**
     * 执行一次任务:流式调 agent-service,阻塞至完成。
     *
     * @param userId  归属用户
     * @param jobId   任务 id
     * @param runId   本次运行 id
     * @param prompt  任务提示词
     * @param logFile 结果日志落盘路径(abs path)
     * @return 运行结果(exitCode 0=成功 1=失败 124=超时; logTail 为截面日志)
     */
    public RunResult run(Long userId, Long jobId, Long runId, String prompt, String logFile) {
        String threadId = "da-job-" + jobId + "-" + runId;
        StringBuilder buf = new StringBuilder();
        AtomicReference<String> lastError = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JobOutcome> outcome = new AtomicReference<>(JobOutcome.FAILED);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("thread_id", threadId);
            // 任务用系统默认模型(第一个启用的 provider + 其首个启用模型);未配置则回退 agent-service env
            try {
                ProviderConfigService.RuntimeModel rm = providerConfigService.resolveRuntimeModel(null, null);
                if (rm != null) {
                    if (rm.modelId() != null && !rm.modelId().isBlank()) body.put("model", rm.modelId());
                    if (rm.baseUrl() != null && !rm.baseUrl().isBlank()) body.put("base_url", rm.baseUrl());
                    if (rm.apiKey() != null && !rm.apiKey().isBlank()) body.put("api_key", rm.apiKey());
                }
            } catch (Exception e) {
                log.warn("resolve runtime model for job failed: {}", e.toString());
            }
            // 无人值守任务:agent_type=task → agent-service 关闭所有 HITL 中断、不挂 ask_user,
            // 保证任务一路执行到底(不会向用户提问/要授权)。
            body.put("agent_type", "task");
            ArrayNode msgs = body.putArray("messages");
            ObjectNode m = msgs.addObject();
            m.put("role", "user");
            m.put("content", prompt);

            Flux<ServerSentEvent<String>> flux = webClient.post()
                    .uri("/api/users/{userId}/stream", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .publishOn(Schedulers.boundedElastic());

            flux.subscribe(
                    sse -> handle(threadId, sse, buf, lastError, outcome),
                    err -> {
                        lastError.compareAndSet(null, err.toString());
                        outcome.set(JobOutcome.FAILED);
                        done.countDown();
                    },
                    () -> done.countDown());

            boolean finished = done.await(props.getTimeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                outcome.set(JobOutcome.TIMEOUT);
                buf.append("\n\n[任务超时] 超过 ").append(props.getTimeoutMinutes()).append(" 分钟强制中断");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.set(JobOutcome.FAILED);
            lastError.compareAndSet(null, "任务等待被中断: " + e.toString());
        } catch (Exception e) {
            outcome.set(JobOutcome.FAILED);
            lastError.compareAndSet(null, e.toString());
        }

        int exitCode = switch (outcome.get()) {
            case SUCCESS -> 0;
            case TIMEOUT -> 124;
            default -> 1;
        };
        String err = lastError.get();
        if (err != null && !err.isBlank() && !buf.toString().endsWith(err)) {
            buf.append("\n\n[错误] ").append(err);
        }
        String logTail = buf.toString();
        if (logFile != null && !logFile.isBlank()) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(logFile);
                java.nio.file.Files.createDirectories(p.getParent());
                java.nio.file.Files.writeString(p, logTail, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("write job log {} failed: {}", logFile, e.toString());
            }
        }
        log.info("job run {} (user={} job={}) outcome={} exit={}",
                runId, userId, jobId, outcome.get(), exitCode);
        return new RunResult(exitCode, exitCode == 0 ? 1 : 0, logTail);
    }

    private void handle(String threadId, ServerSentEvent<String> sse, StringBuilder buf,
                        AtomicReference<String> lastError, AtomicReference<JobOutcome> outcome) {
        String type = sse.event();
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(data);
            switch (type == null ? "" : type) {
                case "delta" -> {
                    String text = payload.has("content") ? payload.get("content").asText() : "";
                    if (!text.isEmpty()) buf.append(text);
                }
                case "tool" -> {
                    String name = payload.path("name").asText("tool");
                    buf.append("\n\n[tool] ").append(name).append("\n");
                    if (payload.has("args")) buf.append(payload.get("args").toString());
                }
                case "tool_result" -> {
                    String name = payload.path("name").asText("tool");
                    String output = payload.has("output") && !payload.get("output").isNull()
                            ? payload.get("output").toString()
                            : payload.path("content").asText("");
                    buf.append("\n[tool_result] ").append(name).append("\n").append(clip(output, 4000));
                }
                case "error" -> {
                    String msg = payload.has("description") ? payload.get("description").asText("")
                            : payload.path("message").asText("");
                    lastError.compareAndSet(null, msg);
                    outcome.set(JobOutcome.FAILED);
                }
                case "done" -> outcome.set(JobOutcome.SUCCESS);
                default -> {
                    // start / message.start / message.end 等帧:任务执行无需记录
                }
            }
        } catch (Exception e) {
            log.debug("handle job sse failed (type={}): {}", type, e.toString());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 任务运行结果(与旧容器执行器契约对齐,便于调度器复用)。 */
    public record RunResult(int exitCode, int delivered, String logTail) {
    }

    private enum JobOutcome {
        SUCCESS, FAILED, TIMEOUT
    }
}