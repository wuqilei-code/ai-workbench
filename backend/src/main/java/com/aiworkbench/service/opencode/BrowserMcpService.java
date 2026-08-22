package com.aiworkbench.service.opencode;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.service.agent.BrowserContainerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关侧的 playwright-mcp(共享浏览器)JSON-RPC 客户端。
 * <p>
 * 每用户 agent 容器内由 agent-entrypoint.sh 常驻运行 playwright-mcp(容器内 8931),opencode 与网关都
 * 是该 MCP 服务器的 HTTP 客户端;服务端配置 PLAYWRIGHT_MCP_ISOLATED=false + --shared-browser-context,
 * 因此本网关发起的截图/接管动作与 AI(opencode)看到的是同一浏览器上下文。
 * <p>
 * 实现为最小 streamable-HTTP JSON-RPC 客户端(仅 initialize / tools/call),每个用户维护一个 MCP 会话
 * (Mcp-Session-Id),会话失效(404)时自动重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserMcpService {

    /** opencode 客户端名(playwright-mcp 端展示/绑定用) */
    private static final String CLIENT_NAME = "ai-workbench-gateway";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(10);

    private final BrowserContainerManager agentContainerManager;
    private final ObjectMapper objectMapper;

    private final AtomicLong idSeq = new AtomicLong();
    private final Map<Long, String> sessions = new ConcurrentHashMap<>();

    /**
     * 调用某用户共享浏览器的 MCP 工具。返回 JSON-RPC 响应的 result 对象
     * (如 {content:[...]});调用失败抛 {@link BusinessException}。
     */
    public Map<String, Object> callTool(Long userId, String tool, Map<String, Object> arguments) {
        String sessionId = ensureSession(userId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        try {
            return send(userId, sessionId, rpc(userId, "tools/call", params));
        } catch (SessionLostException e) {
            // 会话失效(agent 容器重启等):丢弃缓存并重试一次
            sessions.remove(userId);
            return send(userId, ensureSession(userId), rpc(userId, "tools/call", params));
        }
    }

    // ============ 内部:streamable HTTP JSON-RPC ============

    private String ensureSession(Long userId) {
        String cached = sessions.get(userId);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> init = rpc(userId, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", CLIENT_NAME, "version", "1")));
        String sessionId = sendInit(userId, init);
        sessions.put(userId, sessionId);
        // 通知初始化完成(playwright-mcp 非必需,但符合规范)
        try {
            WebClient web = webClient(userId);
            web.post().uri("/mcp")
                    .header("Mcp-Session-Id", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(rpc(userId, "notifications/initialized", Map.of()))
                    .retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
        } catch (Exception ignore) {
            // 通知失败不影响后续 tools/call
        }
        return sessionId;
    }

    private String sendInit(Long userId, Map<String, Object> request) {
        try {
            ResponseEntity<byte[]> entity = webClient(userId).post().uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .exchangeToMono(r -> r.toEntity(byte[].class))
                    .block(INIT_TIMEOUT);
            if (entity == null) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(), "浏览器 MCP 无响应");
            }
            if (entity.getStatusCode().value() == 404) {
                throw new SessionLostException();
            }
            String sessionId = entity.getHeaders().getFirst("Mcp-Session-Id");
            if (sessionId == null || sessionId.isBlank()) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                        "浏览器 MCP initialize 未返回会话标识");
            }
            // 校验 initialize 结果(含错误即抛)
            parseResult(entity, request);
            return sessionId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "浏览器 MCP 连接失败: " + shortMessage(e));
        }
    }

    private Map<String, Object> send(Long userId, String sessionId, Map<String, Object> request) {
        ResponseEntity<byte[]> entity;
        try {
            entity = webClient(userId).post().uri("/mcp")
                    .header("Mcp-Session-Id", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .exchangeToMono(r -> r.toEntity(byte[].class))
                    .block(CALL_TIMEOUT);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "浏览器 MCP 调用失败: " + shortMessage(e));
        }
        if (entity == null) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(), "浏览器 MCP 无响应");
        }
        if (entity.getStatusCode().value() == 404) {
            throw new SessionLostException();
        }
        return parseResult(entity, request);
    }

    /**
     * 解析 JSON-RPC 响应(application/json 或 text/event-stream),返回 result 对象。
     * JSON-RPC error 与 HTTP 4xx/5xx 统一转为 BusinessException。
     */
    private Map<String, Object> parseResult(ResponseEntity<byte[]> entity, Map<String, Object> request) {
        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(), "浏览器 MCP 返回空响应");
        }
        int status = entity.getStatusCode().value();
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode rpc = null;
        MediaType mt = entity.getHeaders().getContentType();
        if (mt != null && mt.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            rpc = parseSseJson(text, request);
        } else {
            try {
                rpc = objectMapper.readTree(text);
            } catch (Exception e) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                        "浏览器 MCP 响应解析失败(status=" + status + "): " + text);
            }
        }
        if (rpc == null) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "浏览器 MCP 响应为空(status=" + status + ")");
        }
        JsonNode error = rpc.get("error");
        if (error != null && !error.isNull()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "浏览器工具调用失败: " + error);
        }
        JsonNode result = rpc.get("result");
        if (result == null || result.isNull()) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "浏览器 MCP 响应缺少 result: " + text);
        }
        return objectMapper.convertValue(result, Map.class);
    }

    /** 从 SSE 事件流解析出与请求 id 匹配的 JSON-RPC 响应(容错:取首个带 result/error 的 data)。 */
    private JsonNode parseSseJson(String text, Map<String, Object> request) {
        Object reqId = request.get("id");
        JsonNode fallback = null;
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (!l.startsWith("data:")) {
                continue;
            }
            String payload = l.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode id = node.get("id");
                if (reqId != null && id != null && !id.isNull() && reqId.toString().equals(id.toString())) {
                    return node;
                }
                if (node.has("result") || node.has("error")) {
                    fallback = node;
                }
            } catch (Exception ignore) {
                // 忽略无法解析的 SSE 行
            }
        }
        return fallback;
    }

    private Map<String, Object> rpc(Long userId, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", idSeq.incrementAndGet());
        m.put("method", method);
        m.put("params", params);
        return m;
    }

    private WebClient webClient(Long userId) {
        return WebClient.builder()
                .baseUrl(agentContainerManager.browserMcpUrl(userId))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    private String shortMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName()
                : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    /** 会话失效(HTTP 404 表示 session 不存在),调用方重建会话后重试。 */
    private static final class SessionLostException extends RuntimeException {
    }
}
