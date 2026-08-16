package com.aiworkbench.dto.my;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 个人驾驶舱聚合结果。 */
@Data
@Builder
public class MyStatsVo {
    private Overview overview;
    /** 最近 N 天每日序列(补零,日期升序) */
    private List<DailyPoint> daily;
    /** agent 分布(会话数) */
    private List<NamedStat> agents;
    /** 模型维度:会话数 + token 消耗 + 估算成本 */
    private List<ModelStat> models;

    @Data
    @Builder
    public static class Overview {
        private long totalSessions;
        private long totalMessages;
        private long userMessages;
        private long assistantMessages;
        private long inputTokens;
        private long outputTokens;
        private long reasoningTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private long toolCalls;
        private long filesChanged;
        private long compactionCount;
        private long unreadNotifications;
        // 派生指标
        private double avgMessagesPerSession;
        private long activeDays;
        private long currentStreak;
        // 环比(最近 7 天 vs 前 7 天)
        private long weekMessages;
        private long prevWeekMessages;
        private long weekOutputTokens;
        private long prevWeekOutputTokens;
        // 成本(元,按 model_price 估算;无单价的模型按 0)
        private double totalEstimatedCost;
        // 定时任务
        private long totalTasks;
        private long enabledTasks;
        private long totalRuns;
        private long successRuns;
        private long failedRuns;
        // 资产
        private long installedSkills;
        private long publishedSkills;
        private long publishedSkillInstalls;
        private long privateMcps;
        private long myTemplates;
        private long favorites;
    }

    @Data
    @Builder
    public static class DailyPoint {
        private String date;
        private long messages;
        private long inputTokens;
        private long outputTokens;
        private long toolCalls;
        private long sessions;
        private long filesChanged;
    }

    @Data
    @Builder
    public static class NamedStat {
        private String name;
        private long count;
    }

    @Data
    @Builder
    public static class ModelStat {
        private String model;
        private long sessions;
        private long inputTokens;
        private long outputTokens;
        private long reasoningTokens;
        private double estimatedCost;
    }
}
