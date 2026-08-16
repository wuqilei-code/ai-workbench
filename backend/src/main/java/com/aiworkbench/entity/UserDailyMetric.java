package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户每日指标汇总表(方案 B):按 user_id + 日期 聚合 chat_message 的热指标,
 * 供驾驶舱趋势查询,避免运行时扫 chat_message 大表 / 解析 JSON。
 */
@Data
@TableName("user_daily_metrics")
public class UserDailyMetric {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private LocalDate statDate;

    /** 可见消息数(user/assistant,不含压缩占位) */
    private Long messages;
    private Long userMessages;
    private Long assistantMessages;

    private Long inputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;
    private Long toolCalls;

    /** 当日活跃会话数(distinct session) */
    private Long sessions;
    /** 当日变更文件数(summary 消息 summaryFiles 长度之和) */
    private Long filesChanged;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
