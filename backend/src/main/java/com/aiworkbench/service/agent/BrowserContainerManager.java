package com.aiworkbench.service.agent;

import com.aiworkbench.config.WorkspaceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 每用户沙箱容器里的浏览器端点访问(deepagents 单轨,execute 与浏览器同容器)。
 * <p>
 * 容器由 agent-service 创建(挂 aiwb-agents 网 + 浏览器常驻入口),网关按容器名直连:
 * playwright-mcp(8931)/MCP、shared Chromium CDP(9223)。本类只负责:
 * - 按用户推导容器名与 CDP/MCP 地址;
 * - 探测容器就绪;未就绪时请求 agent-service 确保创建(网关自身不再用 docker CLI 建容器)。
 */
@Slf4j
@Component
public class BrowserContainerManager {

    /** playwright-mcp 端口(浏览器入口脚本启动) */
    public static final int BROWSER_MCP_PORT = 8931;
    /** CDP 转发端口(node 把容器内 127.0.0.1:9222 转发到 0.0.0.0:9223,可跨容器访问) */
    public static final int BROWSER_CDP_PORT = 9223;
    private static final java.time.Duration CMD_TIMEOUT = java.time.Duration.ofSeconds(20);

    private final WorkspaceProperties props;
    private final WebClient webClient;

    public BrowserContainerManager(
            WorkspaceProperties props,
            @Value("${deepagents.base-url:http://localhost:8000}") String agentBaseUrl) {
        this.props = props;
        String base = (agentBaseUrl == null || agentBaseUrl.isBlank()) ? "http://localhost:8000" : agentBaseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder().baseUrl(base).build();
    }

    public String containerName(Long userId) {
        // 与 agent-service 沙箱命名一致(aiwb-sandbox-u<user_id>)
        return "aiwb-sandbox-u" + userId;
    }

    /** playwright-mcp Streamable HTTP 地址(网关同 aiwb-agents 网,按容器名 DNS 直连) */
    public String browserMcpUrl(Long userId) {
        return "http://" + containerName(userId) + ":" + BROWSER_MCP_PORT + "/mcp";
    }

    /** CDP 地址(实时截图流 / 接管) */
    public String browserCdpBaseUrl(Long userId) {
        return "http://" + containerName(userId) + ":" + BROWSER_CDP_PORT;
    }

    /** 沙箱容器是否在运行。 */
    public boolean isReady(Long userId) {
        try {
            Result r = run(List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName(userId)));
            return r.ok() && "true".equals(r.output().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 兼容旧调用(容器生命周期由 agent-service 管理,无空闲回收回调)。 */
    public void touch(Long userId) {
        // no-op
    }

    /** 确保沙箱(含浏览器 daemon)就绪:不在运行时请 agent-service 创建。失败静默,后续按 isReady 轮询。 */
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
                    .block(java.time.Duration.ofSeconds(20));
            log.info("requested sandbox ensure for user {}", userId);
        } catch (Exception e) {
            log.warn("sandbox ensure request failed (user={}): {}", userId, e.toString());
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