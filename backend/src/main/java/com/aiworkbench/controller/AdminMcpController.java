package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.admin.McpRequest;
import com.aiworkbench.dto.admin.McpVo;
import com.aiworkbench.service.McpConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理员公共 MCP Server 配置(所有用户共享)。 */
@RestController
@RequestMapping("/api/admin/mcp")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMcpController {

    private final McpConfigService mcpConfigService;

    @GetMapping
    public R<List<McpVo>> list() {
        return R.ok(mcpConfigService.listPublic());
    }

    @PostMapping
    @AuditLog(action = "创建公共MCP配置")
    public R<McpVo> create(@Valid @RequestBody McpRequest req) {
        return R.ok(mcpConfigService.createPublic(req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "更新公共MCP配置", targetSpel = "#id")
    public R<McpVo> update(@PathVariable Long id, @RequestBody McpRequest req) {
        return R.ok(mcpConfigService.updatePublic(id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "删除公共MCP配置", targetSpel = "#id")
    public R<Void> delete(@PathVariable Long id) {
        mcpConfigService.deletePublic(id);
        return R.ok();
    }
}