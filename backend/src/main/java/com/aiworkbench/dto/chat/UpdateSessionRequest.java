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
    /** 切换会话 agent(可选) */
    private String agent;
}
