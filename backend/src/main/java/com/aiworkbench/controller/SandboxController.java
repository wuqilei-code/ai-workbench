package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.service.agent.BrowserContainerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 沙箱生命周期(浏览器面板「关闭浏览器」按钮)。
 * <p>
 * POST /api/agent/sandbox/stop —— 停止当前用户的沙箱容器,立即释放 CPU/内存;
 * 容器保留不删除,下次打开面板 / AI 使用浏览器时按需重建并拉起浏览器栈。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final BrowserContainerManager browserContainerManager;

    @PostMapping("/stop")
    public R<Map<String, Object>> stop() {
        Long userId = SecurityUtil.currentUserId();
        browserContainerManager.stop(userId);
        return R.ok(Map.of("status", "ok"));
    }
}
