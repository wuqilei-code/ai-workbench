package com.aiworkbench.service.opencode;

import com.aiworkbench.service.agent.BrowserContainerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 直连共享 Chromium 的 CDP 客户端(每用户 agent 容器一个)。
 * <p>
 * agent 容器内 agent-entrypoint.sh 以 {@code --remote-debugging-port=9222} 启动一个真实 Chromium,
 * 并经 node 转发 0.0.0.0:9223 → 127.0.0.1:9222(新版 Chromium 强制 DevTools 只绑 loopback)。
 * 本客户端经容器网络直连其 CDP WebSocket(用容器 IP 作 Host,绕开 Chromium 的 Host 校验):
 * <ul>
 *   <li>实时推帧:Page.startScreencast → Page.screencastFrame 事件(base64 JPEG)由 Chromium 主动推送,
 *       收到后回 Page.screencastFrameAck,帧转发给 {@code frameSink}(网关再推给前端 WS);</li>
 *   <li>输入接管:Input.dispatchMouseEvent / Input.dispatchKeyEvent / Input.insertText / Page.navigate 等
 *       直发 CDP,与 AI(opencode 经 playwright-mcp --cdp-endpoint)操作同一浏览器上下文。</li>
 * </ul>
 * 每次连接会从 /json/list 解析一个 page target(优先非 about:blank 的活动页面)并连接其 WebSocket。
 * <p>
 * 采用 JDK 内置 java.net.http.WebSocket(而非 ReactorNetty),行为与实测可用的原生 WS 客户端一致。
 */
@Slf4j
public class BrowserCdpClient {

    private static final int CDP_PORT = BrowserContainerManager.BROWSER_CDP_PORT;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CMD_TIMEOUT_SECONDS = Duration.ofSeconds(8);

    /**
     * screencast 输出会被 Chromium 缩到 maxWidth/maxHeight 内(如 1920 视口→1600 帧)。
     * 前端画布按帧像素发坐标,而 Input.* 需要页面 CSS 像素坐标——必须乘 inputScale 换算。
     * 缩放比由每帧 screencastFrame.metadata.deviceWidth/Height(视口 CSS 尺寸)实时计算。
     */
    private static final int FRAME_MAX_WIDTH = 1600;
    private static final int FRAME_MAX_HEIGHT = 1000;
    private static final int FRAME_QUALITY = 70;

    /** 无头 Chromium 页面视口尺寸(Emulation.setDeviceMetricsOverride):
     *  默认 800x600 太小,放大到面板高度会糊;设为宽屏 1600x900 使画面在大分辨率下清晰,
     *  FRAME_MAX_WIDTH=1600 恰好能原尺寸推帧,无需降采样。 */
    private static final int VIEWPORT_W = 1600;
    private static final int VIEWPORT_H = 900;

    /** 帧像素 → CSS 像素的缩放比(>=1);由最新一帧的视口元数据维护。 */
    private volatile double inputScale = 1.0;
    /** 最近一次已知视口 CSS 尺寸(captureFrame 保持与推流同尺寸用;-1 未知)。 */
    private volatile int lastViewportW = -1;
    private volatile int lastViewportH = -1;

    private static final Map<String, Integer> KEY_VK = Map.ofEntries(
            Map.entry("Enter", 13), Map.entry("Backspace", 8), Map.entry("Tab", 9),
            Map.entry("Escape", 27), Map.entry("Delete", 46), Map.entry("Space", 32),
            Map.entry("ArrowUp", 38), Map.entry("ArrowDown", 40),
            Map.entry("ArrowLeft", 37), Map.entry("ArrowRight", 39),
            Map.entry("Home", 36), Map.entry("End", 35),
            Map.entry("PageUp", 33), Map.entry("PageDown", 34));

    private final Long userId;
    private final BrowserContainerManager agentContainerManager;
    private final ObjectMapper objectMapper;

    private final AtomicLong idSeq = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean screencasting = new AtomicBoolean(false);

    private volatile WebSocket ws;
    private volatile Consumer<byte[]> frameSink;
    private volatile String attachedTargetId;

    public BrowserCdpClient(Long userId, BrowserContainerManager agentContainerManager, ObjectMapper objectMapper) {
        this.userId = userId;
        this.agentContainerManager = agentContainerManager;
        this.objectMapper = objectMapper;
    }

    public boolean isConnected() {
        WebSocket w = ws;
        return connected.get() && w != null;
    }

    public boolean isScreencasting() {
        return screencasting.get();
    }

    /** 当前已附加的 page target id(用于周期对比,AI 切换标签时跟随)。 */
    public String attachedTargetId() {
        return attachedTargetId;
    }

    /** 重新解析当前最合适的 page target id(未变返回 null;无则返回 null)。 */
    public String bestPageId() {
        try {
            return resolveBestPageId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前已附加 page target 的页面坐标偏移与视口(chromeOffset:窗口→页面;null=未知)。 */
    public int[] pageOffset() {
        try {
            JsonNode r = call("Runtime.evaluate", Map.of(
                    "expression", "({ox:Math.round((window.outerWidth-window.innerWidth)/2)||0, oy:(window.outerHeight-window.innerHeight)||0, vw:window.innerWidth||0, vh:window.innerHeight||0})",
                    "returnByValue", true));
            JsonNode val = r.path("result").path("result").path("value");
            return new int[]{val.path("ox").asInt(), val.path("oy").asInt(),
                    val.path("vw").asInt(), val.path("vh").asInt()};
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出所有页面标签(id/title/url),供面板标签栏展示。 */
    public java.util.List<java.util.Map<String, Object>> tabs() {
        java.util.List<java.util.Map<String, Object>> tabs = new java.util.ArrayList<>();
        try {
            for (JsonNode t : fetchTargets()) {
                if ("page".equals(t.path("type").asText(""))) {
                    java.util.Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.path("id").asText(""));
                    m.put("title", t.path("title").asText(""));
                    m.put("url", t.path("url").asText(""));
                    tabs.add(m);
                }
            }
        } catch (Exception ignore) {
            // 连接波动时返回空列表
        }
        return tabs;
    }

    /** 激活指定标签(切换到该页面并 bringToFront,有头窗口随即显示该页)。 */
    public boolean activateTab(String targetId) {
        try {
            if (!connectToTarget(targetId)) {
                return false;
            }
            call("Page.bringToFront", Map.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 新建一个标签页(about:blank)并切换到它。 */
    public boolean createTab() {
        try {
            JsonNode r = call("Target.createTarget", Map.of("url", "about:blank"));
            String id = r.path("result").path("targetId").asText("");
            if (id.isEmpty()) {
                return false;
            }
            // 新建 target 可能短暂不在 /json/list 中,重试激活
            for (int i = 0; i < 5; i++) {
                if (activateTab(id)) {
                    return true;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 将当前附加的页面 bringToFront(让有头窗口显示该页)。 */
    public boolean bringFrontActive() {
        try {
            call("Page.bringToFront", Map.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 关闭指定标签页,成功后自动切到剩余页面中最合适的一个。 */
    public boolean closeTab(String targetId) {
        try {
            if (targetId.equals(attachedTargetId)) {
                call("Target.closeTarget", Map.of("targetId", targetId));
                // 关闭当前页后重连到剩余页面
                return connectToTarget(null);
            }
            call("Target.closeTarget", Map.of("targetId", targetId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 连接共享 Chromium 的 page target;失败返回 false(调用方稍后重试)。 */
    public boolean connect() {
        try {
            return connectToTarget(null);
        } catch (Exception e) {
            log.info("cdp[{}] connect failed: {}", userId, shortMsg(e));
            return false;
        }
    }

    /** 连接到指定 page target(为 null 时自动挑选最合适的),并连接其 WebSocket。 */
    public boolean connectToTarget(String targetId) {
        closeInternal();
        String wsUrl;
        try {
            wsUrl = resolvePageWsUrl(targetId);
        } catch (Exception e) {
            log.debug("cdp[{}] resolve page target failed: {}", userId, shortMsg(e));
            return false;
        }
        try {
            HttpClient hc = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            CompletableFuture<WebSocket> fut = hc.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener());
            WebSocket w = fut.get(5, TimeUnit.SECONDS);
            if (w == null) {
                log.debug("cdp[{}] ws handshake returned null ({})", userId, wsUrl);
                return false;
            }
            this.ws = w;
            log.debug("cdp[{}] connected to {}", userId, wsUrl);
            return connected.get();
        } catch (Exception e) {
            log.info("cdp[{}] connect failed ({}): {}", userId, wsUrl, shortMsg(e));
            return false;
        }
    }

    /** 启动 screencast 并注册帧回调;连接成功后调用。 */
    public void startScreencast(Consumer<byte[]> sink) {
        this.frameSink = sink;
        try {
            call("Emulation.setDeviceMetricsOverride", Map.of(
                    "width", VIEWPORT_W,
                    "height", VIEWPORT_H,
                    "deviceScaleFactor", 1,
                    "mobile", false));
            call("Page.enable", Map.of());
            call("Page.startScreencast", Map.of(
                    "format", "jpeg",
                    "quality", FRAME_QUALITY,
                    "maxWidth", FRAME_MAX_WIDTH,
                    "maxHeight", FRAME_MAX_HEIGHT,
                    "everyNthFrame", 1));
            screencasting.set(true);
        } catch (Exception e) {
            throw new IllegalStateException("启动 screencast 失败: " + shortMsg(e), e);
        }
    }

    public void stopScreencast() {
        screencasting.set(false);
        try {
            call("Page.stopScreencast", Map.of());
        } catch (Exception ignore) {
            // 连接可能已断
        }
    }

    /** 主动抓一帧(用户操作后推送,即使页面无变化也给出可见反馈)。
     *  用 clip+scale 让输出与 screencast 帧同尺寸,避免画布在两种尺寸间来回重置。 */
    public void captureFrame() {
        WebSocket w = ws;
        if (w == null || !connected.get()) {
            return;
        }
        long id = idSeq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "jpeg");
        params.put("quality", FRAME_QUALITY);
        int dw = lastViewportW;
        int dh = lastViewportH;
        if (dw > 0 && dh > 0) {
            double k = Math.min(1.0, Math.min((double) FRAME_MAX_WIDTH / dw, (double) FRAME_MAX_HEIGHT / dh));
            params.put("clip", Map.of("x", 0, "y", 0, "width", dw, "height", dh, "scale", k));
        }
        sendRaw(rpc(id, "Page.captureScreenshot", params));
        f.thenAccept(node -> {
            String data = node.path("result").path("data").asText("");
            Consumer<byte[]> sink = frameSink;
            if (!data.isEmpty() && sink != null) {
                sink.accept(Base64.getDecoder().decode(data));
            }
        }).exceptionally(e -> null);
    }

    public void close() {
        stopScreencast();
        closeInternal();
    }

    // ============ CDP 命令(阻塞,await 响应) ============

    /** 帧像素坐标 → 页面 CSS 像素坐标(推流被缩放时必须换算,否则点击错位)。 */
    private int sx(int x) {
        return (int) Math.round(x * inputScale);
    }

    public void click(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mousePressed", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 1, "clickCount", 1));
        call("Input.dispatchMouseEvent", Map.of("type", "mouseReleased", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 0, "clickCount", 1));
    }

    /** 右键点击(有头浏览器会弹出原生右键菜单,出现在窗口画面里)。 */
    public void rightClick(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mousePressed", "x", sx(x), "y", sx(y),
                "button", "right", "buttons", 2, "clickCount", 1));
        call("Input.dispatchMouseEvent", Map.of("type", "mouseReleased", "x", sx(x), "y", sx(y),
                "button", "right", "buttons", 0, "clickCount", 1));
    }

    /** 拖拽:按下后移动再释放 */
    public void press(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mousePressed", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 1, "clickCount", 1));
    }

    public void release(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mouseReleased", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 0, "clickCount", 1));
    }

    /** 拖拽过程中的移动(按键按下状态) */
    public void drag(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 1));
    }

    public void move(int x, int y) throws Exception {
        call("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", sx(x), "y", sx(y),
                "button", "none", "buttons", 0));
    }

    /** 高频拖拽移动:fire-and-forget 不等 CDP 响应(页面繁忙时阻塞等待会造成输入队列队头堵塞)。 */
    public void dragAsync(int x, int y) {
        notify("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", sx(x), "y", sx(y),
                "button", "left", "buttons", 1));
    }

    /** 高频悬停移动:同 dragAsync。 */
    public void moveAsync(int x, int y) {
        notify("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", sx(x), "y", sx(y),
                "button", "none", "buttons", 0));
    }

    public void wheel(int x, int y, int deltaX, int deltaY) throws Exception {
        // 先移动光标到滚动位置,再派发滚轮;否则无头下 mouseWheel 可能不生效
        call("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", sx(x), "y", sx(y),
                "button", "none", "buttons", 0));
        call("Input.dispatchMouseEvent", Map.of("type", "mouseWheel", "x", sx(x), "y", sx(y),
                "deltaX", deltaX, "deltaY", deltaY));
    }

    public void key(String key) throws Exception {
        if (key == null || key.isEmpty()) return;
        int vk = keyVirtualKey(key);
        String code = keyCode(key);
        boolean printable = key.length() == 1 && !Character.isISOControl(key.charAt(0));
        Map<String, Object> down = new LinkedHashMap<>();
        down.put("type", "keyDown");
        down.put("key", key);
        down.put("code", code);
        down.put("windowsVirtualKeyCode", vk);
        // 可打印字符必须带 text,否则 Chrome 只派发按键事件、不插入字符
        if (printable) {
            down.put("text", key);
            down.put("unmodifiedText", key);
        }
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("type", "keyUp");
        up.put("key", key);
        up.put("code", code);
        up.put("windowsVirtualKeyCode", vk);
        call("Input.dispatchKeyEvent", down);
        call("Input.dispatchKeyEvent", up);
    }

    public void type(String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        call("Input.insertText", Map.of("text", text));
    }

    public void navigate(String url) throws Exception {
        call("Page.navigate", Map.of("url", url));
    }

    public void reload() throws Exception {
        call("Page.reload", Map.of());
    }

    public void back() throws Exception {
        evaluate("history.back()");
    }

    public void forward() throws Exception {
        evaluate("history.forward()");
    }

    private void evaluate(String expression) throws Exception {
        call("Runtime.evaluate", Map.of("expression", expression));
    }

    // ============ 内部:WS 消息处理(java.net.http.WebSocket) ============

    private WebSocket.Listener listener() {
        // 大帧(base64 JPEG)screencastFrame 会被分成多个 onText 片段(last=false),必须拼到 last=true 再解析
        final StringBuilder msgBuf = new StringBuilder(1024 * 1024);
        return new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                connected.set(true);
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                msgBuf.append(data);
                if (last) {
                    String full = msgBuf.toString();
                    msgBuf.setLength(0);
                    try {
                        handleText(full);
                    } catch (Exception e) {
                        log.debug("cdp[{}] handle message failed: {}", userId, shortMsg(e));
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.debug("cdp[{}] ws error: {}", userId, shortMsg(error));
                onClosed();
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.debug("cdp[{}] ws closed ({})", userId, statusCode);
                onClosed();
                return null;
            }
        };
    }

    private void handleText(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (node.hasNonNull("id")) {
            long id = node.get("id").asLong();
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) {
                f.complete(node);
            }
            return;
        }
        String method = node.path("method").asText("");
        if ("Page.screencastFrame".equals(method)) {
            JsonNode params = node.get("params");
            String data = params == null ? "" : params.path("data").asText("");
            JsonNode sidNode = params == null ? null : params.get("sessionId");
            // 从帧元数据维护视口尺寸与输入缩放比(视口/窗口变化自动跟随)
            JsonNode meta = params == null ? null : params.path("metadata");
            if (meta != null && meta.isObject()) {
                int dw = meta.path("deviceWidth").asInt(0);
                int dh = meta.path("deviceHeight").asInt(0);
                if (dw > 0 && dh > 0) {
                    lastViewportW = dw;
                    lastViewportH = dh;
                    double k = Math.min(1.0, Math.min(
                            (double) FRAME_MAX_WIDTH / dw, (double) FRAME_MAX_HEIGHT / dh));
                    if (k > 1e-6) {
                        inputScale = 1.0 / k;
                    }
                }
            }
            if (!data.isEmpty()) {
                byte[] frame = Base64.getDecoder().decode(data);
                // 先回 ack 再交给 sink:screencast 是 ack 限速,重活(sink)若阻塞 ack 会拖慢推帧
                ackScreencastFrame(sidNode);
                Consumer<byte[]> sink = frameSink;
                if (sink != null) {
                    sink.accept(frame);
                }
            } else {
                ackScreencastFrame(sidNode);
            }
        }
        // 其余事件(Page.frameNavigated 等)忽略
    }

    /** screencast 帧必须回 ack,否则 Chromium 停止推帧;sessionId 须保留原始类型(整数/字符串)。 */
    private void ackScreencastFrame(JsonNode sessionId) {
        if (sessionId == null || sessionId.isNull()) {
            return;
        }
        Object sid = sessionId.isIntegralNumber() ? sessionId.asInt() : sessionId.asText();
        sendRaw(rpc(idSeq.incrementAndGet(), "Page.screencastFrameAck", Map.of("sessionId", sid)));
    }

    private void onClosed() {
        connected.set(false);
        screencasting.set(false);
        this.ws = null;
        if (!pending.isEmpty()) {
            RuntimeException err = new IllegalStateException("CDP 连接已断开");
            pending.values().forEach(f -> f.completeExceptionally(err));
            pending.clear();
        }
    }

    private void closeInternal() {
        WebSocket w = ws;
        ws = null;
        connected.set(false);
        try {
            if (w != null) {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        } catch (Exception ignore) {
            // 忽略
        }
        onClosed();
    }

    // ============ 内部:命令收发 ============

    private CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
        long id = idSeq.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        sendRaw(rpc(id, method, params));
        return f;
    }

    private JsonNode call(String method, Map<String, Object> params) throws Exception {
        WebSocket w = ws;
        if (w == null || !connected.get()) {
            throw new IllegalStateException("浏览器 CDP 未连接");
        }
        return send(method, params).get(CMD_TIMEOUT_SECONDS.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 只发不等(不注册 pending):用于高频输入事件,避免悬挂 Future 与队头阻塞。 */
    private void notify(String method, Map<String, Object> params) {
        sendRaw(rpc(idSeq.incrementAndGet(), method, params));
    }

    private void sendRaw(String json) {
        WebSocket w = ws;
        if (w == null) {
            return;
        }
        try {
            w.sendText(json, true);
        } catch (Exception e) {
            log.debug("cdp[{}] send failed: {}", userId, shortMsg(e));
        }
    }

    private String rpc(long id, String method, Map<String, Object> params) {
        try {
            // 注意:Chromium CDP 只接受 id/method/sessionId/params 四个字段,不能带 jsonrpc 等多余字段(-32600 拒绝)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("method", method);
            m.put("params", params == null ? Map.of() : params);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 CDP 命令失败", e);
        }
    }

    // ============ 内部:page target 解析 ============

    /** 从 /json/list 解析一个 page target 的 WebSocket 地址,并把主机改写成容器 IP(DNS 直连)。 */
    private String resolvePageWsUrl(String targetId) {
        JsonNode best;
        if (targetId != null && !targetId.isEmpty()) {
            best = fetchTargetById(targetId);
        } else {
            best = resolveBestPage();
        }
        String wsUrl = best.path("webSocketDebuggerUrl").asText("");
        if (wsUrl.isEmpty()) {
            throw new IllegalStateException("page target 缺少 webSocketDebuggerUrl");
        }
        attachedTargetId = best.path("id").asText("");
        return rewriteHost(wsUrl, cdpBaseUrl());
    }

    private JsonNode fetchTargetById(String targetId) {
        JsonNode list = fetchTargets();
        for (JsonNode t : list) {
            if (targetId.equals(t.path("id").asText(""))) {
                return t;
            }
        }
        throw new IllegalStateException("page target 不存在: " + targetId);
    }

    private String resolveBestPageId() {
        JsonNode best = resolveBestPage();
        return best == null ? null : best.path("id").asText("");
    }

    /** 拉取 /json/list 全部目标。 */
    private JsonNode fetchTargets() {
        String base = cdpBaseUrl();
        JsonNode list = WebClient.builder()
                .baseUrl(base)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build()
                .get().uri("/json/list")
                .retrieve().bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalStateException("CDP 无可用页面目标");
        }
        return list;
    }

    /** 从 /json/list 挑出最合适的 page target(优先非 about:blank 的活动页面)。 */
    private JsonNode resolveBestPage() {
        JsonNode list = fetchTargets();
        JsonNode best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (JsonNode target : list) {
            if (!"page".equals(target.path("type").asText(""))) {
                continue;
            }
            String url = target.path("url").asText("");
            boolean real = !url.isBlank() && !url.startsWith("about:")
                    && !url.startsWith("chrome://") && !url.startsWith("devtools://");
            int priority = real ? 0 : 1;
            if (priority < bestPriority) {
                bestPriority = priority;
                best = target;
            }
        }
        if (best == null) {
            throw new IllegalStateException("CDP 无 page target");
        }
        return best;
    }

    /**
     * CDP 端点基址:优先用容器 IP(而非容器名)——新版 Chromium DevTools 校验 Host 头,
     * 非 localhost/IP 的容器名(如 aiwb-agent-36)会被 500 拒绝。每次连接现解析,容器重建换 IP 也跟随。
     */
    private String cdpBaseUrl() {
        // k8s NodePort 模式:compose 侧无沙箱 DNS,直接走 <node>:<nodePort>。
        if (agentContainerManager.nodePortMode()) {
            return agentContainerManager.browserCdpBaseUrl(userId);
        }
        try {
            String host = agentContainerManager.containerName(userId);
            if (java.net.InetAddress.getByName(host).isLoopbackAddress()) {
                return "http://127.0.0.1:" + CDP_PORT;
            }
            String ip = java.net.InetAddress.getByName(host).getHostAddress();
            return "http://" + ip + ":" + CDP_PORT;
        } catch (Exception e) {
            return agentContainerManager.browserCdpBaseUrl(userId);
        }
    }

    /** Chromium 报告的 webSocketDebuggerUrl 指向 127.0.0.1,从网关容器访问必须改为容器 IP。 */
    private String rewriteHost(String wsUrl, String httpBase) {
        String authority = httpBase.substring("http://".length());
        int pathStart = wsUrl.indexOf('/', "://".length() + 3);
        String path = pathStart < 0 ? "" : wsUrl.substring(pathStart);
        return "ws://" + authority + path;
    }

    // ============ 内部:按键映射 ============

    private static int keyVirtualKey(String key) {
        Integer vk = KEY_VK.get(key);
        if (vk != null) {
            return vk;
        }
        return key.isEmpty() ? 0 : key.charAt(0);
    }

    private static String keyCode(String key) {
        switch (key) {
            case "Enter": return "Enter";
            case "Backspace": return "Backspace";
            case "Tab": return "Tab";
            case "Escape": return "Escape";
            case "Delete": return "Delete";
            case "ArrowUp": return "ArrowUp";
            case "ArrowDown": return "ArrowDown";
            case "ArrowLeft": return "ArrowLeft";
            case "ArrowRight": return "ArrowRight";
            case "Home": return "Home";
            case "End": return "End";
            case "PageUp": return "PageUp";
            case "PageDown": return "PageDown";
            case "Space": return "Space";
            default:
                if (key.length() == 1 && Character.isLetterOrDigit(key.charAt(0))) {
                    return "Key" + Character.toUpperCase(key.charAt(0));
                }
                return "";
        }
    }

    private static String shortMsg(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName()
                : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}