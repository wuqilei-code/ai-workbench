package com.aiworkbench.controller;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.R;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.browser.BrowserActionRequest;
import com.aiworkbench.service.opencode.BrowserMcpService;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享浏览器实时面板 + 用户接管(与 AI 使用同一浏览器上下文)。
 * <p>
 * <ul>
 *   <li>GET /api/agent/browser/live —— SSE 实时截图流(约 1.5s/帧,复用后端到 agent 容器 playwright-mcp 的会话);</li>
 *   <li>POST /api/agent/browser/action —— 用户接管动作(点击坐标/滚动/导航/输入等,见工具白名单)。</li>
 * </ul>
 * 依赖 agent 容器内 agent-entrypoint.sh 常驻的 playwright-mcp daemon(容器内 8931,共享浏览器上下文)。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/browser")
@RequiredArgsConstructor
public class BrowserControlController {

    /** 面板可调用的接管工具白名单(避免开放 RCE 类工具之外的任意方法;browser_run_code_unsafe 与用户
     *  在自己 agent 里已有的 bash 权限等价,仅用于向聚焦元素输入文字)。 */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "browser_navigate",
            "browser_navigate_back",
            "browser_mouse_click_xy",
            "browser_mouse_move_xy",
            "browser_mouse_wheel",
            "browser_run_code_unsafe",
            "browser_take_screenshot");

    /** 实时帧间隔(ms) */
    private static final long FRAME_INTERVAL_MS = 1500;
    /** 错误状态上报节流:同一错误文案至少间隔(ms)上报一次,避免刷屏 */
    private static final long ERROR_THROTTLE_MS = 5000;

    private final BrowserMcpService browserMcpService;
    private final OpencodeProcessManager opencodeProcessManager;
    private final ObjectMapper objectMapper;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() {
        Long userId = SecurityUtil.currentUserId();
        SseEmitter emitter = new SseEmitter(0L);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "browser-live-" + userId);
            t.setDaemon(true);
            return t;
        });
        emitter.onCompletion(executor::shutdownNow);
        emitter.onTimeout(executor::shutdownNow);
        executor.submit(() -> streamLoop(userId, emitter));
        return emitter;
    }

    @PostMapping("/action")
    public R<Map<String, Object>> action(@RequestBody BrowserActionRequest req) {
        Long userId = SecurityUtil.currentUserId();
        if (req.getTool() == null || !ALLOWED_TOOLS.contains(req.getTool())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "不允许的浏览器接管工具: " + req.getTool());
        }
        if (!opencodeProcessManager.isReady(userId)) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(), "agent 未启动,请先发起会话");
        }
        Map<String, Object> result = browserMcpService.callTool(userId, req.getTool(), req.getArgs());
        return R.ok(result);
    }

    // ============ 内部:实时截图流 ============

    private void streamLoop(Long userId, SseEmitter emitter) {
        String lastError = null;
        long lastErrorAt = 0;
        try {
            while (true) {
                if (!opencodeProcessManager.isReady(userId)) {
                    sendState(emitter, "no-agent");
                    sleep(FRAME_INTERVAL_MS);
                    continue;
                }
                try {
                    Map<String, Object> result = browserMcpService.callTool(userId,
                            "browser_take_screenshot", Map.of("scale", "css"));
                    String image = extractImage(result);
                    if (image != null) {
                        sendEvent(emitter, "frame", Map.of("image", image));
                        lastError = null;
                    } else {
                        sendEvent(emitter, "state", Map.of("state", "empty"));
                    }
                } catch (BusinessException e) {
                    String msg = e.getMessage();
                    long now = System.currentTimeMillis();
                    if (!msg.equals(lastError) || now - lastErrorAt > ERROR_THROTTLE_MS) {
                        sendEvent(emitter, "state", Map.of("state", "error", "message", msg));
                        lastError = msg;
                        lastErrorAt = now;
                    }
                }
                sleep(FRAME_INTERVAL_MS);
            }
        } catch (IllegalStateException e) {
            // 客户端已断开:正常结束
            log.debug("browser live stream ended for user {}: {}", userId, e.toString());
        } catch (InterruptedException e) {
            // 线程中断:正常结束
            Thread.currentThread().interrupt();
            log.debug("browser live stream interrupted for user {}: {}", userId, e.toString());
        } catch (Exception e) {
            log.warn("browser live stream error for user {}: {}", userId, e.toString());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignore) {
                // 客户端已断开
            }
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 已关闭
            }
        }
    }

    /** 从 tools/call 结果中提取截图 base64(content 里 type=image 的块)。 */
    private String extractImage(Map<String, Object> result) {
        Object content = result.get("content");
        if (content instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> block && "image".equals(block.get("type"))) {
                    Object data = block.get("data");
                    if (data instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private void sendState(SseEmitter emitter, String state) {
        sendEvent(emitter, "state", Map.of("state", state));
    }

    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 客户端断开时 send 会抛 IllegalStateException
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
