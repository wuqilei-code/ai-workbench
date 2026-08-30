package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.admin.McpRequest;
import com.aiworkbench.dto.admin.McpVo;
import com.aiworkbench.service.McpConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/** 用户私有的 MCP Server 配置(HTTPS/SSE URL)。 */
@RestController
@RequestMapping("/api/chat/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpConfigService mcpConfigService;

    @Value("${deepagents.base-url:http://localhost:8000}")
    private String deepagentsBaseUrl;

    @Value("${app.internal-token:}")
    private String internalToken;

    @GetMapping
    public R<List<McpVo>> list() {
        return R.ok(mcpConfigService.listUser(SecurityUtil.currentUserId()));
    }

    @PostMapping
    @AuditLog(action = "创建MCP配置")
    public R<McpVo> create(@Valid @RequestBody McpRequest req) {
        return R.ok(mcpConfigService.createUser(SecurityUtil.currentUserId(), req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "更新MCP配置", targetSpel = "#id")
    public R<McpVo> update(@PathVariable Long id, @RequestBody McpRequest req) {
        return R.ok(mcpConfigService.updateUser(SecurityUtil.currentUserId(), id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "删除MCP配置", targetSpel = "#id")
    public R<Void> delete(@PathVariable Long id) {
        mcpConfigService.deleteUser(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    /** 手动接口编辑器 AI 辅助:把自然语言描述转成一条接口定义 JSON(转发给 agent-service)。 */
    @PostMapping("/manual/assist")
    public R<Map<String, Object>> manualAssist(@RequestBody Map<String, Object> body) {
        Object resp = agentServicePost("/api/mcp/manual/assist", body);
        return R.ok((Map<String, Object>) resp);
    }

    /** 手动接口编辑器 AI 参数填充:根据已填 method/path/name/description 推断 params/body_fields。 */
    @PostMapping("/manual/assist/params")
    public R<Map<String, Object>> manualAssistParams(@RequestBody Map<String, Object> body) {
        Object resp = agentServicePost("/api/mcp/manual/assist/params", body);
        return R.ok((Map<String, Object>) resp);
    }

    private Object agentServicePost(String uri, Object body) {
        String base = deepagentsBaseUrl.endsWith("/")
                ? deepagentsBaseUrl.substring(0, deepagentsBaseUrl.length() - 1)
                : deepagentsBaseUrl;
        return WebClient.builder()
                .baseUrl(base)
                .defaultHeader("X-Internal-Token", internalToken)
                .build()
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Object.class)
                .block();
    }
}