package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.service.agent.BrowserContainerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AIO 沙箱浏览器/工具连接信息。
 * <p>
 * GET /api/agent/browser/sandbox-info —— 返回当前用户沙箱的 VNC / VSCode / 终端 URL,
 * 前端把这些地址嵌入 iframe 做实时预览与接管(AIO 自带浏览器 + VNC,无需网关注入 CDP)。
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
        return R.ok(browserContainerManager.sandboxInfo(userId));
    }
}
