package com.aiworkbench.service.agent;

import com.aiworkbench.config.WorkspaceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每用户沙箱里的浏览器端点访问(deepagents 单轨,execute 与浏览器同容器)。
 * <p>
 * 沙箱由 agent-service 创建,网关按同一 DNS 名直连(docker 版=容器名挂 aiwb-agents 网;
 * k8s 版=同名 headless Service 转发到沙箱 Pod):playwright-mcp(8931)/MCP、shared
 * Chromium CDP(9223)。本类只负责:
 * - 按用户推导沙箱名与 CDP/MCP 地址(两套部署共用同一 DNS 命名);
 * - 探测沙箱就绪(HTTP 探测 CDP,替代 docker inspect —— 网关不再依赖 docker sock);
 *   未就绪时请求 agent-service 确保创建。
 */
@Slf4j
@Component
public class BrowserContainerManager {

    /** playwright-mcp 端口(浏览器入口脚本启动) */
    public static final int BROWSER_MCP_PORT = 8931;
    /** CDP 转发端口(node 把容器内 127.0.0.1:9222 转发到 0.0.0.0:9223,可跨容器访问) */
    public static final int BROWSER_CDP_PORT = 9223;

    // --- k8s NodePort 模式(混合架构:沙箱在 kind 集群,网关/compose 侧经节点 NodePort 直连) ---
    // 公式与 agent-service sandbox_k8s._service_port_defs 保持一致:
    //   CDP = base + uid%2000 ; MCP = base + 500 + uid%2000
    private static final int NODEPORT_SPAN = 2000;
    private static final int MCP_NODEPORT_OFFSET = 500;

    private final WorkspaceProperties props;
    private final WebClient webClient;
    private final String sandboxAddrMode;
    private final String sandboxNodeHost;
    private final int nodePortBase;

    /** isReady 结果短 TTL 缓存:输入事件路径高频调用,HTTP 探测不能每次都跑 */
    private static final long READY_CACHE_MS = 800;
    private final Map<Long, CachedReady> readyCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedReady(boolean ready, long at) {
    }

    public BrowserContainerManager(
            WorkspaceProperties props,
            @Value("${deepagents.base-url:http://localhost:8000}") String agentBaseUrl,
            @Value("${app.internal-token:}") String internalToken,
            @Value("${app.sandbox-addr-mode:dns}") String sandboxAddrMode,
            @Value("${app.sandbox-node-host:aiwb-control-plane}") String sandboxNodeHost,
            @Value("${app.sandbox-nodeport-base:30000}") int nodePortBase) {
        this.props = props;
        this.sandboxAddrMode = sandboxAddrMode;
        this.sandboxNodeHost = sandboxNodeHost;
        this.nodePortBase = nodePortBase;
        String base = (agentBaseUrl == null || agentBaseUrl.isBlank()) ? "http://localhost:8000" : agentBaseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder().baseUrl(base)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public String containerName(Long userId) {
        // 与 agent-service 沙箱命名一致(aiwb-sandbox-u<user_id>);
        // k8s 版同名 headless Service,网关集群内按此 DNS 直连 Pod
        return "aiwb-sandbox-u" + userId;
    }

    /** playwright-mcp Streamable HTTP 地址。
     *  nodeport 模式=网关经 <node_host>:<nodePort> 连沙箱 NodePort Service(k8s 混合架构);
     *  dns 模式=网关与沙箱同网/同集群,按沙箱名 DNS 直连(docker compose)。 */
    public String browserMcpUrl(Long userId) {
        if ("nodeport".equalsIgnoreCase(sandboxAddrMode)) {
            int np = nodePortBase + MCP_NODEPORT_OFFSET + (int) (userId % NODEPORT_SPAN);
            return "http://" + sandboxNodeHost + ":" + np + "/mcp";
        }
        return "http://" + containerName(userId) + ":" + BROWSER_MCP_PORT + "/mcp";
    }

    /** CDP 地址(实时截图流 / 接管);模式同上。 */
    public String browserCdpBaseUrl(Long userId) {
        if ("nodeport".equalsIgnoreCase(sandboxAddrMode)) {
            int np = nodePortBase + (int) (userId % NODEPORT_SPAN);
            return "http://" + sandboxNodeHost + ":" + np;
        }
        return "http://" + containerName(userId) + ":" + BROWSER_CDP_PORT;
    }

    /** 沙箱是否就绪(800ms TTL 缓存):HTTP 探测 CDP /json/version,浏览器栈真正起来才 true。
     *   docker/k8s 两套部署同用此探测,网关不再依赖 docker socket。 */
    public boolean isReady(Long userId) {
        CachedReady c = readyCache.get(userId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < READY_CACHE_MS) {
            return c.ready();
        }
        boolean ready = probeCdp(userId);
        readyCache.put(userId, new CachedReady(ready, now));
        return ready;
    }

    private boolean probeCdp(Long userId) {
        try {
            // 绝对 URL 会覆盖 WebClient baseUrl;CDP 探测响应为 JSON,含 "Browser"/webSocketDebuggerUrl
            String url = browserCdpBaseUrl(userId) + "/json/version";
            String body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(3));
            return body != null && (body.contains("\"Browser\"") || body.contains("webSocketDebuggerUrl"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否 k8s NodePort 模式(沙箱在集群,网关经节点 NodePort 直连)。 */
    public boolean nodePortMode() {
        return "nodeport".equalsIgnoreCase(sandboxAddrMode);
    }

    /** 兼容旧调用(容器生命周期由 agent-service 管理,无空闲回收回调)。 */
    public void touch(Long userId) {
        // no-op
    }

    /** 确保沙箱与浏览器栈就绪:不在运行时请 agent-service 创建并懒启动浏览器
     *  (首次拉起 chromium 需数秒,超时放宽到 35s)。失败静默,后续按 isReady 轮询。 */
    public void getOrStart(Long userId) {
        if (isReady(userId)) {
            return;
        }
        try {
            webClient.post()
                    .uri("/api/users/{userId}/sandbox/ensure", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .block(java.time.Duration.ofSeconds(35));
            log.info("requested sandbox ensure for user {}", userId);
        } catch (Exception e) {
            log.warn("sandbox ensure request failed (user={}): {}", userId, e.toString());
        }
    }

    /** 停止沙箱(浏览器面板「关闭浏览器」):请求 agent-service 停沙箱,立即释放资源。
     *   docker 版容器保留不删除、k8s 版删 Pod,后续 ensure 按需重建。 */
    public void stop(Long userId) {
        readyCache.put(userId, new CachedReady(false, System.currentTimeMillis()));
        try {
            webClient.post()
                    .uri("/api/users/{userId}/sandbox/stop", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .block(java.time.Duration.ofSeconds(15));
            log.info("requested sandbox stop for user {}", userId);
        } catch (Exception e) {
            log.warn("sandbox stop request failed (user={}): {}", userId, e.toString());
        }
    }
}