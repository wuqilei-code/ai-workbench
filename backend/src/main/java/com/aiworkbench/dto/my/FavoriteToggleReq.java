package com.aiworkbench.dto.my;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 收藏/取消收藏请求。 */
@Data
public class FavoriteToggleReq {
    @NotNull
    private Long sessionId;
    @NotNull
    private Long messageId;
    private String role;
    private String content;
    private String turnId;
    private String sessionTitle;
}
