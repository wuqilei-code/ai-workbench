package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.admin.McpRequest;
import com.aiworkbench.dto.admin.McpVo;
import com.aiworkbench.service.McpConfigService;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.aiworkbench.service.opencode.process.PerUserConfigWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户私有 MCP Server 配置:每个用户可维护自己的 MCP(local 命令进程 / remote HTTP/SSE URL),
 * 仅对自己生效(叠加管理员公共配置)。保存后推送该用户 opencode.json 副本;若进程活跃则重启生效。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcps")
@RequiredArgsConstructor
public class UserMcpController {

    private final McpConfigService mcpConfigService;
    private final PerUserConfigWriter configWriter;
    private final OpencodeProcessManager processManager;

    /** 当前用户已配置的私有 MCP 列表。 */
    @GetMapping
    public R<List<McpVo>> list() {
        return R.ok(mcpConfigService.listPrivate(SecurityUtil.currentUserId()));
    }

    /** 管理员公共 MCP 列表(只读展示,普通用户可查看不能修改)。 */
    @GetMapping("/public")
    public R<List<McpVo>> listPublic() {
        return R.ok(mcpConfigService.listPublic());
    }

    @PostMapping
    @AuditLog(action = "创建我的MCP")
    public R<McpVo> create(@Valid @RequestBody McpRequest req) {
        Long userId = SecurityUtil.currentUserId();
        McpVo vo = mcpConfigService.createPrivate(userId, req);
        applyForUser(userId);
        return R.ok(vo);
    }

    @PutMapping("/{id}")
    @AuditLog(action = "更新我的MCP", targetSpel = "#id")
    public R<McpVo> update(@PathVariable Long id, @Valid @RequestBody McpRequest req) {
        Long userId = SecurityUtil.currentUserId();
        McpVo vo = mcpConfigService.updatePrivate(userId, id, req);
        applyForUser(userId);
        return R.ok(vo);
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "删除我的MCP", targetSpel = "#id")
    public R<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        mcpConfigService.deletePrivate(userId, id);
        applyForUser(userId);
        return R.ok();
    }

    /** 私有 MCP 变更后:推送该用户 opencode.json 副本;进程活跃则重启使其立即生效,未运行则下次首启生效。 */
    private void applyForUser(Long userId) {
        try {
            configWriter.pushConfig(userId);
            if (processManager.isReady(userId) && !processManager.isStreaming(userId)) {
                processManager.restart(userId);
            } else {
                log.info("user MCP changed: push config to user {} ({}), will take effect on next start",
                        userId, processManager.isReady(userId) ? "streaming, skipped restart" : "inactive");
            }
        } catch (Exception e) {
            log.warn("apply user MCP config failed for user {}: {}", userId, e.toString());
        }
    }
}