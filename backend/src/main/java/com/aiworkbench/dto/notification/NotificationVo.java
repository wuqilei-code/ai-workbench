package com.aiworkbench.dto.notification;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统消息项(右上角铃铛)。 */
@Data
@Builder
public class NotificationVo {
    private Long id;
    private String type;
    private String title;
    private String content;
    /** 跳转链接 */
    private String link;
    private boolean read;
    private LocalDateTime createdAt;
}
