package com.aiworkbench.dto.my;

import lombok.Builder;
import lombok.Data;

/** 收藏项(返回前端)。 */
@Data
@Builder
public class FavoriteVo {
    private Long id;
    private Long sessionId;
    private Long messageId;
    private String role;
    private String content;
    private String turnId;
    private String sessionTitle;
    /** epoch 毫秒 */
    private long createdAt;
}
