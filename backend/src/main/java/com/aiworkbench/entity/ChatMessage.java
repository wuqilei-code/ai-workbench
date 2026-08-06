package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    /** 冗余 user_id,便于鉴权查询 */
    private Long userId;
    private String opencodeMessageId;

    /** user | assistant | tool | system */
    private String role;
    /** 文本内容(MVP 存文本) */
    private String content;
    /** 完整 parts 数组(JSON 字符串),保真 */
    private String parts;

    /** pending | streaming | completed | error | aborted */
    private String status;

    /** 时间戳由 DB 默认值管理,update 时 NEVER 不写入 */
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
