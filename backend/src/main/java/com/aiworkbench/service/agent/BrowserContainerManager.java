package com.aiworkbench.service.agent;

import com.aiworkbench.config.WorkspaceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 每用户 AIO 沙箱容器访问(agent-infra/sandbox 单轨,execute/文件/浏览器同容器)。
 * <p>
 * 容器由 agent-service 创建(挂 aiwb-agents 网,容器名 aio-sandbox-u<userId>),
 * 网关不再直连容器的 CDP/MCP,只负责:
 * - 探测容器是否就绪;
 * - 未就绪时请求 agent-service 确保创建;
 * - 代理 agent-service 的沙箱连接信息(VNC/VSCode/终端 URL)给前端 iframe。
 */
@Slf4j
@Component
public class BrowserContainerManager {

    private static final java.time.Duration CMD_TIMEOUT = java.time.Duration.ofSeconds(20);

    private final WorkspaceProperties props;
    private final WebClient webClient;

    /** isReady 结果短 TTL 缓存:避免高频 spawn docker inspect 子进程 */
    private static final long READY_CACHE_MS = 800;
    private final Map<Long, CachedReady> readyCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedReady(boolean ready, long at) {
    }

    public BrowserContainerManager(
            WorkspaceProperties props,
            @Value("${deepagents.base-url:http://localhost:8000}") String agentBaseUrl,
            @Value("${app.internal-token:}") String internalToken) {
        this.props = props;
        String base = (agentBaseUrl == null || agentBaseUrl.isBlank()) ? "http://localhost:8000" : agentBaseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder().baseUrl(base)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public String containerName(Long userId) {
        // 与 agent-service 沙箱命名一致(aio-sandbox-u<user_id>)
        return "aio-sandbox-u" + userId;
    }

    /** 沙箱容器是否在运行(800ms TTL 缓存,避免输入事件路径高频 spawn docker CLI 子进程)。 */
    public boolean isReady(Long userId) {
        CachedReady c = readyCache.get(userId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < READY_CACHE_MS) {
            return c.ready();
        }
        boolean ready;
        try {
            Result r = run(List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName(userId)));
            ready = r.ok() && "true".equals(r.output().trim());
        } catch (Exception e) {
            ready = false;
        }
        readyCache.put(userId, new CachedReady(ready, now));
        return ready;
    }

    /** 确保沙箱就绪:不在运行时请 agent-service 创建并等待就绪。失败静默,后续按 isReady 轮询。 */
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
                    .block(java.time.Duration.ofSeconds(40));
            log.info("requested sandbox ensure for user {}", userId);
        } catch (Exception e) {
            log.warn("sandbox ensure request failed (user={}): {}", userId, e.toString());
        }
    }

    /** 停止沙箱容器(浏览器面板「关闭浏览器」):请求 agent-service 停容器,立即释放资源。
     *  容器保留不删除,后续 ensure 按需重建。 */
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

    /** 代理 agent-service 的沙箱连接信息(VNC/VSCode/终端 URL),供前端 iframe 使用。 */
    public Map<String, Object> sandboxInfo(Long userId) {
        try {
            Map<String, Object> body = webClient.get()
                    .uri("/sandbox/browser-info/{userId}", userId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block(java.time.Duration.ofSeconds(45));
            return body != null ? body : Map.of("mode", "unknown");
        } catch (Exception e) {
            log.warn("sandbox info request failed (user={}): {}", userId, e.toString());
            return Map.of("mode", "error", "error", e.toString());
        }
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }
    }

    private Result run(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            if (!p.waitFor(CMD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("docker 命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("docker 执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断");
        }
    }
}
