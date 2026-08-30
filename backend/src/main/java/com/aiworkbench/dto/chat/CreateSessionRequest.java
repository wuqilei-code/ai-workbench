package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSessionRequest {
    private String title;
    /** 会话勾选启用的自定义技能名 */
    private java.util.List<String> skills;
    /** 会话级临时启用的 MCP server 名(仅加载这些;不设置=默认全部已启用) */
    private java.util.List<String> mcpServers;
    private String agent;
    private String providerId;
    private String modelId;
}
