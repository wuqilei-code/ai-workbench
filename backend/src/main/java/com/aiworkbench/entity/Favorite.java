package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息收藏(用户问题或 AI 回答)。以 (user_id, session_id, message_id) 唯一。
 */
@Data
@TableName("favorite")
public class Favorite {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long sessionId;
    private Long messageId;

    /** user | assistant */
    private String role;
    /** 消息正文(截断) */
    private String content;
    /** 所在 turn id(跳转定位) */
    private String turnId;
    private String sessionTitle;

    private LocalDateTime createdAt;
}
