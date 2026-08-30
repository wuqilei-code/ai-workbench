package com.aiworkbench.dto.chat;

import lombok.Data;

@Data
public class UpdateSessionRequest {
    private String title;
    private String providerId;
    private String modelId;
    private Integer archived;
    /** 0=取消置顶, 1=置顶 */
    private Integer pinned;
    /** 会话勾选启用的自定义技能名 */
    private java.util.List<String> skills;
    /** 会话级临时启用的 MCP server 名(空数组=本会话不加载 MCP;不传=不修改) */
    private java.util.List<String> mcpServers;
    /** true=清除会话级 MCP 选择,恢复默认(加载全部已启用);与 mcpServers 互斥 */
    private Boolean resetMcpServers;
    /** 切换会话 agent(可选) */
    private String agent;
}
