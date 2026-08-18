package com.aiworkbench.controller;

import com.aiworkbench.security.JwtTokenProvider;
import com.aiworkbench.service.opencode.BrowserCdpClient;
import com.aiworkbench.service.opencode.ScreenTileDiffer;
import com.aiworkbench.service.opencode.process.AgentContainerManager;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 共享浏览器 WebSocket handler(无头浏览器 + CDP screencast 按需推流):
 * <p>
 * agent 容器内无头 Chromium(--remote-debugging-port=9222)+ playwright-mcp(--cdp-endpoint 连同一浏览器),
 * 网关直连 CDP。本 handler:
 * <ul>
 *   <li>画面:Page.startScreencast(无头下正常,页面变化才出帧,~14KB/帧)经 latest-wins 邮箱转前端;</li>
 *   <li>按需:鼠标在面板上才 startScreencast,移出即 stopScreencast(静止/移出 = 0 流量);</li>
 *   <li>输入接管:Input.dispatch* / Page.navigate 等直发 CDP;标签列表/切换/关闭/新建;</li>
 *   <li>无头无窗口 chrome,点击坐标与页面视口 1:1(无需偏移换算),前端标签栏自绘。</li>
 * </ul>
 */
@Slf4j
@Component
public class BrowserWebSocketHandler extends TextWebSocketHandler {

    /** 连接未就绪时的重试/监控间隔(ms) */
    private static final long MONITOR_INTERVAL_MS = 1000;
    /** no-agent 状态上报节流(ms) */
    private static final long NO_AGENT_THROTTLE_MS = 2000;
    /** 错误状态上报节流:同一错误文案至少间隔(ms)上报一次,避免刷屏 */
    private static final long ERROR_THROTTLE_MS = 5000;
    /** 服务端兜底拉起 agent 的节流(ms):no-agent 时按需 getOrStart,不再依赖前端 warmup 消息 */
    private static final long WARMUP_INTERVAL_MS = 15000;

    private final JwtTokenProvider tokenProvider;
    private final OpencodeProcessManager processManager;
    private final AgentContainerManager agentContainerManager;
    private final ObjectMapper objectMapper;

    /** 活跃连接:sessionId → session(用于清理) */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    /** 帧处理器:单线程串行处理 screencast 帧,lateset-wins 丢弃积压(只处理最新帧)。 */
    private static final class FrameProcessor {
        final ExecutorService exec;
        final AtomicReference<byte[]> latest = new AtomicReference<>();
        final AtomicBoolean busy = new AtomicBoolean(false);
        final Consumer<byte[]> processor;

        FrameProcessor(String name, Consumer<byte[]> processor) {
            this.processor = processor;
            this.exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
        }

        void submit(byte[] jpeg) {
            latest.set(jpeg);
            if (busy.compareAndSet(false, true)) {
                exec.submit(this::drain);
            }
        }

        void drain() {
            try {
                byte[] f;
                while ((f = latest.getAndSet(null)) != null) {
                    processor.accept(f);
                }
            } finally {
                busy.set(false);
                if (latest.get() != null && busy.compareAndSet(false, true)) {
                    exec.submit(this::drain);
                }
            }
        }

        void shutdown() {
            exec.shutdownNow();
        }
    }

    public BrowserWebSocketHandler(JwtTokenProvider tokenProvider,
                                   OpencodeProcessManager processManager,
                                   AgentContainerManager agentContainerManager,
                                   ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.processManager = processManager;
        this.agentContainerManager = agentContainerManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = authenticate(session);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("未授权"));
            return;
        }
        session.getAttributes().put("userId", userId);
        activeSessions.put(session.getId(), session);

        // 每会话脏矩形差分器 + 帧处理器(解码/差分/编码在独立线程,避免阻塞 CDP 接收线程)
        session.getAttributes().put("tileDiffer", new ScreenTileDiffer());
        session.getAttributes().put("tileProcessor",
                new FrameProcessor("browser-tile-" + userId, jpeg -> processScreenFrame(session, jpeg)));

        // 单一出站发送线程:所有 sendMessage 都经它,避免并发写同一 session
        ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "browser-send-" + userId);
            t.setDaemon(true);
            return t;
        });
        session.getAttributes().put("sendExecutor", sendExecutor);

        AtomicReference<BrowserCdpClient> cdpRef = new AtomicReference<>();
        session.getAttributes().put("cdpClient", cdpRef);
        // 按需推流开关:鼠标在预览面板上才推帧,移出即停(默认 true)
        session.getAttributes().put("active", new AtomicBoolean(true));

        Thread monitor = new Thread(() -> monitorLoop(session, userId, cdpRef), "browser-monitor-" + userId);
        monitor.setDaemon(true);
        session.getAttributes().put("monitorThread", monitor);
        monitor.start();

        log.debug("browser ws connected: user={}, session={}", userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null || !session.isOpen()) return;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.has("action") ? node.get("action").asText() : "";
            handleInput(session, userId, action, node);
        } catch (Exception e) {
            sendError(session, "输入处理失败: " + shortMsg(e));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        log.info("browser ws closed: session={}, status={}", session.getId(), status);
        shutdown(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        activeSessions.remove(session.getId());
        log.info("browser ws transport error: session={}, ex={}", session.getId(), exception == null ? "null" : exception.toString());
        shutdown(session);
    }

    // ============ 监控循环:CDP 生命周期 + 按需 screencast ============

    private void monitorLoop(WebSocketSession session, Long userId, AtomicReference<BrowserCdpClient> cdpRef) {
        BrowserCdpClient cdp = null;
        Set<String> knownTargets = new HashSet<>();
        String lastError = null;
        long lastErrorAt = 0;
        long lastNoAgentAt = 0;
        long lastWarmupAt = 0;
        int tick = 0;
        while (session.isOpen()) {
            try {
                // 面板开着 = 活跃使用:每 tick 刷新活动时间,防止空闲回收在推流中间停掉 agent 容器
                processManager.touch(userId);
                boolean ready = processManager.isReady(userId);
                log.debug("browser monitor: ready={} cdp={} connected={}", ready,
                        cdp == null ? "null" : "obj", cdp != null && cdp.isConnected());
                if (!ready) {
                    closeQuiet(cdp);
                    cdp = null;
                    cdpRef.set(null);
                    knownTargets.clear();
                    long now = System.currentTimeMillis();
                    if (now - lastNoAgentAt > NO_AGENT_THROTTLE_MS) {
                        sendState(session, "no-agent");
                        lastNoAgentAt = now;
                        log.info("browser monitor: sent no-agent for user {}", userId);
                    }
                    // 服务端兜底:按需拉起 agent(与前端 warmup 相同),getOrStart 阻塞至就绪,
                    // 避免用户侧面板在冷启动/容器重启期间长期停留在「agent 未启动」。
                    if (now - lastWarmupAt > WARMUP_INTERVAL_MS) {
                        lastWarmupAt = now;
                        try {
                            long w0 = System.currentTimeMillis();
                            processManager.getOrStart(userId);
                            log.info("browser monitor: server warmup getOrStart done for user {} in {}ms",
                                    userId, System.currentTimeMillis() - w0);
                        } catch (Exception e) {
                            log.debug("browser no-agent warmup failed for user {}: {}", userId, shortMsg(e));
                        }
                    }
                    sleep(MONITOR_INTERVAL_MS);
                    continue;
                }

                if (cdp == null) {
                    cdp = new BrowserCdpClient(userId, agentContainerManager, objectMapper);
                    cdpRef.set(cdp);
                }
                if (!cdp.isConnected()) {
                    if (!cdp.connect()) {
                        throw new IllegalStateException("无法连接浏览器 CDP");
                    }
                    log.debug("browser monitor: cdp connected for user {}", userId);
                    cdp.bringFrontActive();
                    pushTabs(session, cdp);
                    for (Map<String, Object> t : cdp.tabs()) {
                        knownTargets.add(String.valueOf(t.get("id")));
                    }
                    lastError = null;
                } else if (++tick % 5 == 0) {
                    // 跟随 AI:出现新标签页则自动切到该页
                    boolean changed = false;
                    for (Map<String, Object> t : cdp.tabs()) {
                        String id = String.valueOf(t.get("id"));
                        if (knownTargets.add(id)) {
                            if (cdp.activateTab(id)) {
                                changed = true;
                                break;
                            }
                        }
                    }
                    if (changed) {
                        pushTabs(session, cdp);
                    }
                }
                // 按需推流:active 才 startScreencast,否则 stop
                syncScreencast(session, cdp);
            } catch (Throwable t) {
                closeQuiet(cdp);
                cdp = null;
                cdpRef.set(null);
                String msg = shortMsg(t);
                long now = System.currentTimeMillis();
                if (!msg.equals(lastError) || now - lastErrorAt > ERROR_THROTTLE_MS) {
                    sendError(session, "浏览器连接失败: " + msg);
                    lastError = msg;
                    lastErrorAt = now;
                }
            }
            sleep(MONITOR_INTERVAL_MS);
        }
        closeQuiet(cdp);
    }

    private boolean isActive(WebSocketSession session) {
        Object o = session.getAttributes().get("active");
        return !(o instanceof AtomicBoolean ab) || ab.get();
    }

    /** 按 active 开关同步 screencast:active=true 且未推流则启动,active=false 且推流中则停止。 */
    private void syncScreencast(WebSocketSession session, BrowserCdpClient cdp) {
        boolean active = isActive(session);
        try {
            if (active && !cdp.isScreencasting()) {
                cdp.startScreencast(bytes -> onScreenFrame(session, bytes));
            } else if (!active && cdp.isScreencasting()) {
                cdp.stopScreencast();
            }
        } catch (Exception e) {
            log.debug("browser sync screencast failed: {}", shortMsg(e));
        }
    }

    /** screencast 帧回调(运行在 CDP 接收线程):仅投递到帧处理器,不做重活。 */
    private void onScreenFrame(WebSocketSession session, byte[] jpeg) {
        Object o = session.getAttributes().get("tileProcessor");
        if (o instanceof FrameProcessor fp) {
            fp.submit(jpeg);
        }
    }

    /** 帧处理(独立线程):解码整帧 → 脏矩形差分 → 只发变化区域(每帧至多编码一次)。 */
    private void processScreenFrame(WebSocketSession session, byte[] jpeg) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                return;
            }
            ScreenTileDiffer differ = (ScreenTileDiffer) session.getAttributes().get("tileDiffer");
            if (differ == null) {
                return;
            }
            ScreenTileDiffer.Result r = differ.process(img);
            if (r == null) {
                return;
            }
            if (r.resized()) {
                sendJson(session, Map.of("type", "frame-meta", "width", r.w(), "height", r.h()));
            }
            pushRect(session, r.x(), r.y(), r.w(), r.h(), r.jpeg());
        } catch (Exception e) {
            log.debug("browser dirty-rect differ failed: {}", shortMsg(e));
        }
    }

    private void closeQuiet(BrowserCdpClient cdp) {
        if (cdp != null) {
            try { cdp.close(); } catch (Exception ignore) { /* 忽略 */ }
        }
    }

    // ============ 输入事件处理 ============

    private void handleInput(WebSocketSession session, Long userId, String action, JsonNode node) {
        if (!processManager.isReady(userId)) {
            sendError(session, "agent 未启动");
            return;
        }
        Object ref = session.getAttributes().get("cdpClient");
        BrowserCdpClient cdp = ref instanceof AtomicReference<?> ar
                ? (BrowserCdpClient) ar.get() : null;
        if (cdp == null || !cdp.isConnected()) {
            // 面板刚打开时客户端可能仍在连,短暂等待就绪再处理,避免操作被丢弃
            for (int i = 0; i < 6 && cdp != null && !cdp.isConnected(); i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (cdp == null || !cdp.isConnected()) {
                sendError(session, "浏览器连接中,请稍候…");
                return;
            }
        }
        try {
            switch (action) {
                case "active" -> {
                    boolean on = !node.has("on") || node.get("on").asBoolean();
                    Object ao = session.getAttributes().get("active");
                    if (ao instanceof AtomicBoolean ab) {
                        ab.set(on);
                    }
                    syncScreencast(session, cdp);
                }
                case "tabs" -> pushTabs(session, cdp);
                case "activate" -> {
                    String id = node.has("id") ? node.get("id").asText() : "";
                    if (!id.isEmpty()) {
                        cdp.activateTab(id);
                    }
                    pushTabs(session, cdp);
                }
                case "newtab" -> {
                    cdp.createTab();
                    pushTabs(session, cdp);
                }
                case "close" -> {
                    String id = node.has("id") ? node.get("id").asText() : "";
                    if (!id.isEmpty()) {
                        cdp.closeTab(id);
                    }
                    pushTabs(session, cdp);
                }
                case "rightclick" -> {
                    cdp.rightClick(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "click" -> {
                    cdp.click(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "press" -> {
                    cdp.press(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "drag" -> {
                    cdp.drag(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "release" -> {
                    cdp.release(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "move" -> {
                    cdp.move(node.has("x") ? node.get("x").asInt() : 0,
                            node.has("y") ? node.get("y").asInt() : 0);
                }
                case "wheel" -> {
                    int x = node.has("x") ? node.get("x").asInt() : 640;
                    int y = node.has("y") ? node.get("y").asInt() : 300;
                    int deltaY = node.has("deltaY") ? node.get("deltaY").asInt() : 0;
                    int deltaX = node.has("deltaX") ? node.get("deltaX").asInt() : 0;
                    cdp.wheel(x, y, deltaX, deltaY);
                }
                case "key" -> {
                    String key = node.has("key") ? node.get("key").asText() : "";
                    cdp.key(key);
                }
                case "type" -> {
                    String text = node.has("text") ? node.get("text").asText() : "";
                    cdp.type(text);
                }
                case "navigate" -> {
                    String url = node.has("url") ? node.get("url").asText() : "";
                    if (!url.isBlank()) {
                        cdp.navigate(url);
                    }
                }
                case "back" -> cdp.back();
                case "forward" -> cdp.forward();
                case "reload" -> cdp.reload();
                default -> sendError(session, "未知操作: " + action);
            }
        } catch (Exception e) {
            sendError(session, "浏览器操作失败: " + shortMsg(e));
        }
    }

    // ============ 标签栏数据 ============

    private void pushTabs(WebSocketSession session, BrowserCdpClient cdp) {
        try {
            List<Map<String, Object>> tabs = cdp.tabs();
            String activeId = cdp.attachedTargetId();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> t : tabs) {
                Map<String, Object> m = new java.util.LinkedHashMap<>(t);
                m.put("active", String.valueOf(t.get("id")).equals(activeId));
                list.add(m);
            }
            sendJson(session, Map.of("type", "tabs", "tabs", list));
        } catch (Exception ignore) {
            // 推送失败忽略
        }
    }

    // ============ WebSocket 消息发送(均经 sendExecutor) ============

    /** 脏矩形回调:每帧至多一个区域,直接交 send 线程发送。 */
    private void pushRect(WebSocketSession session, int x, int y, int w, int h, byte[] jpeg) {
        Object exec = session.getAttributes().get("sendExecutor");
        if (!(exec instanceof ExecutorService es)) {
            return;
        }
        if (es.isShutdown() || es.isTerminated() || jpeg == null || jpeg.length == 0) {
            return;
        }
        es.submit(() -> {
            try {
                if (!session.isOpen()) return;
                byte[] payload = new byte[jpeg.length + 8];
                payload[0] = (byte) ((x >> 8) & 0xFF);
                payload[1] = (byte) (x & 0xFF);
                payload[2] = (byte) ((y >> 8) & 0xFF);
                payload[3] = (byte) (y & 0xFF);
                payload[4] = (byte) ((w >> 8) & 0xFF);
                payload[5] = (byte) (w & 0xFF);
                payload[6] = (byte) ((h >> 8) & 0xFF);
                payload[7] = (byte) (h & 0xFF);
                System.arraycopy(jpeg, 0, payload, 8, jpeg.length);
                session.sendMessage(new BinaryMessage(payload));
            } catch (Exception e) {
                log.debug("browser ws send rect failed: {}", e.toString());
            }
        });
    }

    private void sendState(WebSocketSession session, String state) {
        sendJson(session, Map.of("type", "state", "state", state));
    }

    private void sendError(WebSocketSession session, String message) {
        sendJson(session, Map.of("type", "error", "message", message));
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        Object exec = session.getAttributes().get("sendExecutor");
        ExecutorService es = exec instanceof ExecutorService ? (ExecutorService) exec : null;
        if (es == null || es.isShutdown() || es.isTerminated()) {
            return;
        }
        es.submit(() -> doSendJson(session, payload));
    }

    private void doSendJson(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (!session.isOpen()) return;
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug("browser ws send failed: {}", e.toString());
        }
    }

    // ============ 认证 ============

    private Long authenticate(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri == null) return null;
            String token = UriComponentsBuilder.fromUri(uri).build()
                    .getQueryParams().getFirst("token");
            if (token == null || token.isBlank()) return null;
            var claims = tokenProvider.parse(token);
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            log.debug("browser ws auth failed: {}", e.toString());
            return null;
        }
    }

    // ============ 工具 ============

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void shutdown(WebSocketSession session) {
        Object exec = session.getAttributes().get("sendExecutor");
        if (exec instanceof ExecutorService es) {
            es.shutdownNow();
        }
        Object tp = session.getAttributes().get("tileProcessor");
        if (tp instanceof FrameProcessor fp) {
            fp.shutdown();
        }
        Object monitor = session.getAttributes().get("monitorThread");
        if (monitor instanceof Thread t) {
            t.interrupt();
        }
        Object ref = session.getAttributes().get("cdpClient");
        if (ref instanceof AtomicReference<?> ar && ar.get() instanceof BrowserCdpClient c) {
            try {
                c.close();
            } catch (Exception ignore) {
                // 忽略
            }
        }
    }

    private String shortMsg(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName()
                : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
