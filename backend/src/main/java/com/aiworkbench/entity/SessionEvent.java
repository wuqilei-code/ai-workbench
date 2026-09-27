package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话 SSE 事件出站记录(跨副本广播)。
 * 各实例 dispatch 时写入一行,其它实例按 seq 增量轮询投递给本地订阅者。
 */
@Data
@TableName("session_event")
public class SessionEvent {

    @TableId(type = IdType.AUTO)
    private Long seq;

    private String sessionId;

    /** 产生该事件的实例 id(避免本实例重复投递)。 */
    private String origin;

    /** 事件名:delta/tool/tool_result/permission.asked/message.end ... */
    private String type;

    /** 事件载荷(JSON 文本)。 */
    private String data;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
