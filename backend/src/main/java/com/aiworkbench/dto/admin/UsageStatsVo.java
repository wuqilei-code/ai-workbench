package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 用量统计看板聚合结果(admin)。 */
@Data
@Builder
public class UsageStatsVo {
    private Overview overview;
    /** 最近 N 天每日消息/活跃用户序列(补零,日期升序) */
    private List<DailyPoint> daily;
    /** 消息量最多的用户 Top 10 */
    private List<TopUser> topUsers;

    @Data
    @Builder
    public static class Overview {
        private long totalUsers;
        private long totalSessions;
        private long totalMessages;
        /** 含工具调用的消息数(近似工具调用量) */
        private long totalToolCalls;
        /** 今天(自然日)新增消息数 */
        private long todayMessages;
        /** 最近 7 天活跃用户数 */
        private long activeUsers7d;
    }

    @Data
    @Builder
    public static class DailyPoint {
        private String date;
        private long messages;
        private long users;
    }

    @Data
    @Builder
    public static class TopUser {
        private Long userId;
        private String username;
        private long sessions;
        private long messages;
    }
}
