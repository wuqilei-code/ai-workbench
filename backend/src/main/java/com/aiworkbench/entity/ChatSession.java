package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    /** opencode 的 session id */
    private String opencodeSessionId;
    private String title;
    private String agent;

    /**
     * 可清空为 NULL 的字段。MyBatis-Plus 默认 NOT_NULL 策略会跳过 null,
     * 导致无法清空。必须用 IGNORED 强制 update 时带上(项目记忆教训)。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String providerId;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String modelId;

    /** 0=正常, 1=归档 */
    private Integer archived;

    /** 0=未置顶, 1=置顶(列表优先展示) */
    private Integer pinned;

    /** 时间戳由 DB 管理,update 时 NEVER 不写入(项目记忆教训:避免覆盖自动值) */
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
