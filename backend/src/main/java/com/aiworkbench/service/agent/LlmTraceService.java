package com.aiworkbench.service.agent;

import com.aiworkbench.entity.LlmBlob;
import com.aiworkbench.entity.LlmCall;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.mapper.ChatSessionMapper;
import com.aiworkbench.mapper.LlmBlobMapper;
import com.aiworkbench.mapper.LlmCallMapper;
import com.aiworkbench.entity.ChatMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 模型调用溯源:落库每次调用的元数据 + 请求/响应原文(gzip blob,sha256 去重)。
 * <p>
 * agent-service 通过 SSE {@code llm_call} 事件把 gzip+base64 的 payload 发过来,
 * 这里解码后按 hash 去重入库;查询时按 assistant 消息 id 取明细,再按需解压原文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmTraceService {

    private final LlmCallMapper llmCallMapper;
    private final LlmBlobMapper llmBlobMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    /** 原文保留天数(0/负数=永久保留) */
    @Value("${app.trace.retention-days:30}")
    private int retentionDays;

    /** 落库一次调用;失败只记日志,不影响对话主链路。 */
    public void record(Long userId, Long sessionId, String agentSessionId, String agentMessageId,
                       int callIndex, JsonNode p) {
        try {
            LlmCall call = new LlmCall();
            call.setUserId(userId);
            call.setSessionId(sessionId);
            call.setAgentSessionId(agentSessionId);
            call.setAgentMessageId(agentMessageId);
            call.setCallIndex(callIndex);
            call.setModelId(text(p, "model"));
            call.setInputTokens(p.path("inputTokens").asLong(0));
            call.setOutputTokens(p.path("outputTokens").asLong(0));
            call.setReasoningTokens(p.path("reasoningTokens").asLong(0));
            call.setCacheReadTokens(p.path("cacheReadTokens").asLong(0));
            call.setCacheWriteTokens(p.path("cacheWriteTokens").asLong(0));
            call.setLatencyMs(p.hasNonNull("latencyMs") ? p.get("latencyMs").asInt() : null);
            call.setTtftMs(p.hasNonNull("ttftMs") ? p.get("ttftMs").asInt() : null);
            call.setFinishReason(text(p, "finishReason"));
            call.setStatus(text(p, "status").isEmpty() ? "ok" : text(p, "status"));
            call.setErrorMessage(text(p, "error"));
            call.setToolCalls(p.path("toolCalls").asInt(0));
            call.setMessageCount(p.path("messageCount").asInt(0));
            call.setRequestHash(storeBlob(text(p, "requestB64"), p.path("requestBytes").asInt(0)));
            call.setResponseHash(storeBlob(text(p, "responseB64"), p.path("responseBytes").asInt(0)));
            // 显式用应用时区写入(MySQL 容器默认 UTC,DB DEFAULT 会让前端显示早 8 小时)
            call.setCreatedAt(LocalDateTime.now());
            llmCallMapper.insert(call);
        } catch (Exception e) {
            log.warn("record llm_call failed (session={}, msg={}): {}", agentSessionId, agentMessageId, e.toString());
        }
    }

    /** 解码 base64 gzip → 入库(按 sha256 去重);空内容返回 null。 */
    private String storeBlob(String b64, int ignoredUncompressedSize) throws Exception {
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        byte[] gz = Base64.getDecoder().decode(b64);
        String hash = sha256Hex(gz);
        if (llmBlobMapper.selectById(hash) == null) {
            LlmBlob blob = new LlmBlob();
            blob.setHash(hash);
            blob.setData(gz);
            blob.setSize(gz.length);
            blob.setCreatedAt(LocalDateTime.now());
            llmBlobMapper.insert(blob);
        }
        return hash;
    }

    /** 某条 assistant 消息的全部调用(校验消息归属)。 */
    public List<LlmCall> listByMessage(String agentMessageId, Long userId) {
        ChatMessage m = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getAgentMessageId, agentMessageId)
                .eq(ChatMessage::getUserId, userId)
                .last("limit 1"));
        if (m == null) {
            return List.of();
        }
        return llmCallMapper.selectList(new LambdaQueryWrapper<LlmCall>()
                .eq(LlmCall::getAgentMessageId, agentMessageId)
                .orderByAsc(LlmCall::getCallIndex)
                .orderByAsc(LlmCall::getId));
    }

    /** 整个会话的全部调用(校验会话归属),按时间正序,供时间线展示。 */
    public List<LlmCall> listBySession(Long sessionId, Long userId) {
        if (!ownsSession(sessionId, userId)) {
            return List.of();
        }
        return llmCallMapper.selectList(new LambdaQueryWrapper<LlmCall>()
                .eq(LlmCall::getSessionId, sessionId)
                .orderByAsc(LlmCall::getId)
                .last("limit 1000"));
    }

    /** 会话调用总数(会话级入口的计数徽标,不受消息分页影响)。 */
    public long countBySession(Long sessionId, Long userId) {
        if (!ownsSession(sessionId, userId)) {
            return 0;
        }
        Long n = llmCallMapper.selectCount(new LambdaQueryWrapper<LlmCall>()
                .eq(LlmCall::getSessionId, sessionId));
        return n == null ? 0 : n;
    }

    private boolean ownsSession(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        ChatSession s = chatSessionMapper.selectById(sessionId);
        return s != null && userId.equals(s.getUserId());
    }

    /** 读取一次调用的请求/响应原文(校验归属)。 */
    public String payload(long callId, String side, Long userId) {
        PayloadResult r = payload(callId, side, userId, false);
        return r == null ? null : r.content();
    }

    /** 请求/响应原文读取结果;delta=true 时请求报文只保留相对上一次调用的新增部分。 */
    public record PayloadResult(String content, boolean delta, int omittedMessages) {}

    /**
     * 读取原文;请求侧可返回"增量视图":
     * <p>
     * 同一会话里相邻两次模型调用的 messages 绝大部分是相同前缀(系统提示 + 历史上下文,
     * 也就是厂商 prompt cache 命中的部分)。这些内容每次原样重复,排障时没有价值。
     * delta=true 时对比上一次调用,省略公共前缀,只保留本次新增的消息,并附
     * {@code omittedMessages / previousCallId / delta} 元信息,方便前端提示。
     */
    public PayloadResult payload(long callId, String side, Long userId, boolean delta) {
        LlmCall call = llmCallMapper.selectById(callId);
        if (call == null || (userId != null && !userId.equals(call.getUserId()))) {
            return null;
        }
        String hash = "response".equals(side) ? call.getResponseHash() : call.getRequestHash();
        String text = readBlob(hash, callId);
        if (!delta || !"request".equals(side) || text.isEmpty()) {
            return new PayloadResult(text, false, 0);
        }
        try {
            JsonNode cur = objectMapper.readTree(text);
            LlmCall prev = null;
            if (call.getSessionId() != null) {
                prev = llmCallMapper.selectOne(new LambdaQueryWrapper<LlmCall>()
                        .eq(LlmCall::getUserId, call.getUserId())
                        .eq(LlmCall::getSessionId, call.getSessionId())
                        .lt(LlmCall::getId, call.getId())
                        .isNotNull(LlmCall::getRequestHash)
                        .orderByDesc(LlmCall::getId)
                        .last("limit 1"));
            }
            if (prev == null) {
                return new PayloadResult(text, false, 0);
            }
            String prevText = readBlob(prev.getRequestHash(), prev.getId());
            if (prevText.isEmpty()) {
                return new PayloadResult(text, false, 0);
            }
            JsonNode prevJson = objectMapper.readTree(prevText);
            JsonNode before = prevJson.get("messages");
            JsonNode now = cur.get("messages");
            if (!(before instanceof com.fasterxml.jackson.databind.node.ArrayNode)
                    || !(now instanceof com.fasterxml.jackson.databind.node.ArrayNode)) {
                return new PayloadResult(text, false, 0);
            }
            int common = 0;
            int max = Math.min(before.size(), now.size());
            while (common < max && before.get(common).equals(now.get(common))) {
                common++;
            }
            if (common <= 0) {
                return new PayloadResult(text, false, 0);
            }
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) cur.deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode tail = objectMapper.createArrayNode();
            for (int i = common; i < now.size(); i++) {
                tail.add(now.get(i));
            }
            out.set("messages", tail);
            out.put("delta", true);
            out.put("omittedMessages", common);
            out.put("previousCallId", prev.getId());
            return new PayloadResult(objectMapper.writeValueAsString(out), true, common);
        } catch (Exception e) {
            // 增量对比失败不影响查看完整原文
            log.debug("build llm payload delta failed (call={}): {}", callId, e.toString());
            return new PayloadResult(text, false, 0);
        }
    }

    /** 解压 blob;不存在/失败返回空串。 */
    private String readBlob(String hash, long callId) {
        if (hash == null) {
            return "";
        }
        LlmBlob blob = llmBlobMapper.selectById(hash);
        if (blob == null) {
            return "";
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(blob.getData()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            String text = out.toString(StandardCharsets.UTF_8);
            // 防极端大 payload 撑爆响应/前端渲染
            if (text.length() > 2_000_000) {
                return text.substring(0, 2_000_000) + "\n…[truncated]";
            }
            return text;
        } catch (Exception e) {
            log.warn("decompress llm blob failed (call={}): {}", callId, e.toString());
            return "";
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 清理过期调用记录 + 不再被引用的原文 blob(默认保留 30 天)。 */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int removed = llmCallMapper.delete(new LambdaQueryWrapper<LlmCall>()
                    .lt(LlmCall::getCreatedAt, cutoff));
            int blobs = jdbc.update(
                    "DELETE FROM llm_blob WHERE created_at < ? AND hash NOT IN ("
                            + "SELECT request_hash FROM llm_call WHERE request_hash IS NOT NULL "
                            + "UNION SELECT response_hash FROM llm_call WHERE response_hash IS NOT NULL)",
                    cutoff);
            if (removed > 0 || blobs > 0) {
                log.info("llm trace cleanup: calls={}, blobs={} (retention {}d)", removed, blobs, retentionDays);
            }
        } catch (Exception e) {
            log.warn("llm trace cleanup failed: {}", e.toString());
        }
    }
}
