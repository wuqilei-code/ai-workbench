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
    /** 切换会话 agent(可选) */
    private String agent;
}
