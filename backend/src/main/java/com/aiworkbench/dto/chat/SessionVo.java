package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionVo {
    private Long id;
    private String opencodeSessionId;
    private String title;
    private String agent;
    private String providerId;
    private String modelId;
    private Integer archived;
    /** 0=未置顶, 1=置顶 */
    private Integer pinned;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
