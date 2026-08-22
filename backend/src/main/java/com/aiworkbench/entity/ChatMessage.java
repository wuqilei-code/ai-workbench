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
    /** 执行轨道的外部消息 id(deepagents 合成 "da-msg-..." 前缀) */
    @TableField("agent_message_id")
    private String agentMessageId;

    /** 冗余会话的 provider/model(写时复制),供按模型统计 token/成本 */
    private String providerId;
    private String modelId;

    /** user | assistant | tool | system */
    private String role;
    /** 文本内容(MVP 存文本) */
    private String content;
    /** 完整 parts 数组(JSON 字符串),保真 */
    private String parts;
    /** opencode assistant 消息真实 token 用量(JSON 字符串 {input,output,reasoning,cache:{read,write}});供进度环使用 */
    private String tokens;
    /** 1=压缩产生的总结消息(compaction summary),前端不展示,仅占位避免历史断裂 */
    private Integer compactionSummary;

    // ---------- 方案 A:从 tokens/parts JSON 抽取的数值列(写时落列,驾驶舱聚合用,避免运行时解析) ----------
    /** 输入 token 数 */
    private Long inputTokens;
    /** 输出 token 数 */
    private Long outputTokens;
    /** 思维链(reasoning) token 数 */
    private Long reasoningTokens;
    /** 缓存读 token 数 */
    private Long cacheReadTokens;
    /** 缓存写 token 数 */
    private Long cacheWriteTokens;
    /** 工具调用次数(parts.toolCalls 数组长度) */
    private Integer toolCallCount;

    /** pending | streaming | completed | error | aborted */
    private String status;

    /** 时间戳由 DB 默认值管理,update 时 NEVER 不写入 */
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
