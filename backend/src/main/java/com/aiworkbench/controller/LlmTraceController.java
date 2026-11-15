package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.entity.LlmCall;
import com.aiworkbench.service.agent.LlmTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型调用溯源:查询某条 assistant 消息下的 N 次模型调用明细,以及单次请求/响应原文。
 * 只返回当前用户自己的数据(消息/调用归属校验在 service 内)。
 */
@RestController
@RequestMapping("/api/chat/traces")
@RequiredArgsConstructor
public class LlmTraceController {

    private final LlmTraceService traceService;

    /** 一条 assistant 消息的全部模型调用(按 call_index 升序)。 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam String agentMessageId) {
        Long userId = SecurityUtil.currentUserId();
        List<LlmCall> calls = traceService.listByMessage(agentMessageId, userId);
        return R.ok(calls.stream().map(LlmTraceController::vo).toList());
    }

    /** 整个会话的全部模型调用(时间正序),供"调用记录时间线"入口使用。 */
    @GetMapping("/session/{sessionId}")
    public R<List<Map<String, Object>>> listBySession(@PathVariable Long sessionId) {
        Long userId = SecurityUtil.currentUserId();
        List<LlmCall> calls = traceService.listBySession(sessionId, userId);
        return R.ok(calls.stream().map(LlmTraceController::vo).toList());
    }

    /** 会话调用总数(入口徽标;不受消息分页影响)。 */
    @GetMapping("/session/{sessionId}/count")
    public R<Map<String, Object>> countBySession(@PathVariable Long sessionId) {
        Long userId = SecurityUtil.currentUserId();
        return R.ok(Map.of("count", traceService.countBySession(sessionId, userId)));
    }

    /** 单次调用的请求/响应原文(JSON 文本,已脱敏)。side=request|response;delta=省略与上次相同的缓存前缀 */
    @GetMapping("/{id}/payload")
    public R<Map<String, Object>> payload(@PathVariable long id,
                                          @RequestParam(defaultValue = "request") String side,
                                          @RequestParam(defaultValue = "false") boolean delta) {
        Long userId = SecurityUtil.currentUserId();
        LlmTraceService.PayloadResult r = traceService.payload(id, side, userId, delta);
        if (r == null) {
            return R.fail(404, "not found");
        }
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("side", side);
        m.put("content", r.content());
        m.put("delta", r.delta());
        m.put("omittedMessages", r.omittedMessages());
        return R.ok(m);
    }

    private static Map<String, Object> vo(LlmCall c) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("agentMessageId", c.getAgentMessageId());
        m.put("callIndex", c.getCallIndex());
        m.put("model", c.getModelId());
        m.put("inputTokens", c.getInputTokens());
        m.put("outputTokens", c.getOutputTokens());
        m.put("reasoningTokens", c.getReasoningTokens());
        m.put("cacheReadTokens", c.getCacheReadTokens());
        m.put("cacheWriteTokens", c.getCacheWriteTokens());
        m.put("latencyMs", c.getLatencyMs());
        m.put("ttftMs", c.getTtftMs());
        m.put("finishReason", c.getFinishReason());
        m.put("status", c.getStatus());
        m.put("error", c.getErrorMessage());
        m.put("toolCalls", c.getToolCalls());
        m.put("messageCount", c.getMessageCount());
        m.put("hasRequest", c.getRequestHash() != null);
        m.put("hasResponse", c.getResponseHash() != null);
        m.put("createdAt", c.getCreatedAt() == null ? null
                : c.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
        return m;
    }
}
