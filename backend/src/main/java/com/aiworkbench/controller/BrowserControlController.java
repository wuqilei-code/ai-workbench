package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.service.agent.BrowserContainerManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * AIO 沙箱浏览器/工具连接信息。
 * <p>
 * GET /api/agent/browser/sandbox-info —— 返回当前用户沙箱的 VNC / VSCode / 终端地址,
 * 前端把这些地址嵌入 iframe 做实时预览与接管(AIO 自带浏览器 + VNC,无需网关注入 CDP)。
 * <p>
 * 单端口方案:沙箱容器在宿主上发布的是随机端口,不能直接给远端用户。这里返回**同源相对路径**
 * (/vnc/、/code-server/、/terminal),由 app 的 nginx 把这些根路径反代到
 * {@code aio-sandbox-u<uid>:8080}。对外只暴露本服务一个端口,且沙箱应用使用的根绝对路径
 * 在同一 origin 下天然成立(code-server 等无需改 base path)。
 * <p>
 * 鉴权:nginx 对反代路径发起 auth_request 子请求到 {@link #aioAuth()}(见 docker/nginx.conf
 * 的 /__aio_auth),由 JwtAuthFilter 校验 aiwb_token;通过后从响应头 X-Aio-Uid 取当前用户,
 * 作为上游沙箱,客户端无法伪造。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/browser")
@RequiredArgsConstructor
public class BrowserControlController {

    private final BrowserContainerManager browserContainerManager;

    @GetMapping("/sandbox-info")
    public R<Map<String, Object>> sandboxInfo() {
        Long userId = SecurityUtil.currentUserId();
        // 首次打开面板时按需拉起沙箱(ensure + 等待就绪由 agent-service 内部完成)
        browserContainerManager.getOrStart(userId);
        Map<String, Object> info = browserContainerManager.sandboxInfo(userId);

        Map<String, Object> out = new HashMap<>();
        out.put("mode", info.getOrDefault("mode", "aio"));
        if (info.get("vnc_url") != null) {
            // 同源相对路径:iframe 以本服务 origin 解析,nginx 反代到沙箱(含鉴权)
            // 用 noVNC 精简版 vnc_lite.html:无顶部控制栏;scale=true 自适应面板大小。
            out.put("vnc_url", "/vnc/vnc_lite.html?scale=true");
            out.put("code_server_url", "/code-server/");
            out.put("terminal_url", "/terminal");
            out.put("base_url", "/");
            out.put("cdp_url", info.get("cdp_url"));
        } else {
            out.put("vnc_url", null);
            out.put("error", info.get("error"));
        }
        return R.ok(out);
    }

    /**
     * 导入本地导出的 cookie 到沙箱浏览器(登录态),使沙箱免登录访问对应站点。
     * 请求体可为 cookie 数组,或 {cookies:[...]};兼容 Cookie-Editor / Playwright storageState 等格式。
     */
    @PostMapping("/cookies")
    public R<Map<String, Object>> importCookies(@RequestBody JsonNode body) {
        Long userId = SecurityUtil.currentUserId();
        JsonNode cookies = body.isArray() ? body : body.path("cookies");
        if (!cookies.isArray() || cookies.isEmpty()) {
            return R.ok(Map.of("status", "error", "error", "需要 cookie 数组(或 {cookies:[...]})"));
        }
        // 首次导入时按需拉起沙箱(浏览器未起则 cdp 不可用)
        browserContainerManager.getOrStart(userId);
        return R.ok(browserContainerManager.importCookies(userId, cookies.toString()));
    }

    /**
     * 沙箱反代的鉴权子请求(仅 nginx auth_request 内部调用,见 docker/nginx.conf 的 /__aio_auth)。
     * 复用 JwtAuthFilter:未认证直接 401;通过后把当前用户 id 放入响应头 X-Aio-Uid,
     * 由 nginx 取用作为上游沙箱(aio-sandbox-u&lt;uid&gt;)——客户端无法伪造。
     */
    @GetMapping("/aio-auth")
    public ResponseEntity<Void> aioAuth() {
        Long userId = SecurityUtil.currentUserId();
        return ResponseEntity.ok().header("X-Aio-Uid", String.valueOf(userId)).build();
    }
}
