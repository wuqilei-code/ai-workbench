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
    /** 执行轨道的外部会话 id(deepagents 合成 "da-" 前缀) */
    @TableField("agent_session_id")
    private String agentSessionId;
    private String title;
    private String agent;

    /** 会话勾选启用的自定义技能名(JSON 数组文本,如 ["data-cleaner"];空=仅内置技能) */
    private String skills;

    /**
     * 会话级临时启用的 MCP server 名(JSON 数组文本,如 ["fetch"];NULL=未设置,
     * 默认加载该用户全部已启用的 MCP;空数组 "[]"=本会话不加载任何 MCP)。
     * IGNORED:支持"恢复默认"把会话选择清空为 NULL(项目记忆教训:默认 NOT_NULL 跳过 null)。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String mcpServers;

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
