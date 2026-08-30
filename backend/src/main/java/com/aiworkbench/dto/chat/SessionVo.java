package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionVo {
    private Long id;
    /** 执行轨道的外部会话 id("da-" 前缀,SSE 订阅 key) */
    private String agentSessionId;
    private String title;
    private String agent;
    /** 会话勾选启用的自定义技能名(空=仅内置技能) */
    private java.util.List<String> skills;
    /** 会话级临时启用的 MCP server 名(NULL=未设置,默认全部已启用;空数组=本会话不加载) */
    private java.util.List<String> mcpServers;
    private String providerId;
    private String modelId;
    private Integer archived;
    /** 0=未置顶, 1=置顶 */
    private Integer pinned;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
