package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次模型调用的溯源记录。
 * <p>
 * 一轮对话里 agent 会调用模型 N 次(每次工具执行后都要再问一次),{@code chat_message}
 * 只保存回合级 token 汇总;本表保存每次调用的模型、用量、耗时、finish_reason 与
 * 请求/响应原文的 blob 引用,用于排障、成本归因与审计溯源。
 */
@Data
@TableName("llm_call")
public class LlmCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long sessionId;

    private String agentSessionId;

    /** assistant 消息的外部 id(da-msg-*),按消息查明细 */
    private String agentMessageId;

    /** 本轮第几次模型调用(1 起) */
    private Integer callIndex;

    private String modelId;

    private Long inputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;

    private Integer latencyMs;
    private Integer ttftMs;

    private String finishReason;

    /** ok | error */
    private String status;
    private String errorMessage;

    private Integer toolCalls;
    private Integer messageCount;

    /** gzip 请求/响应体 sha256(指向 llm_blob) */
    private String requestHash;
    private String responseHash;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
