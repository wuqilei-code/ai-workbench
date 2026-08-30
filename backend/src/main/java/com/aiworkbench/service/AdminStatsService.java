package com.aiworkbench.service;

import com.aiworkbench.dto.admin.UsageStatsVo;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.mapper.ChatSessionMapper;
import com.aiworkbench.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用量统计看板(admin)聚合服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final UserMapper userMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    /** 管理员用量看板聚合缓存(短 TTL),避免高频刷新重复扫库。 */
    private final com.aiworkbench.common.TtlCache<String, UsageStatsVo> cache;

    /** 最近 days 天的用量统计(默认 14)。 */
    public UsageStatsVo usage(int days) {
        int n = Math.max(1, Math.min(days, 90));
        String key = "admin:" + n;
        UsageStatsVo cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusDays(n - 1L).atStartOfDay();
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime todayStart = today.atStartOfDay();

        UsageStatsVo.Overview overview = UsageStatsVo.Overview.builder()
                .totalUsers(userMapper.countTotal())
                .totalSessions(chatSessionMapper.countTotal())
                .totalMessages(chatMessageMapper.countTotal())
                .totalToolCalls(chatMessageMapper.countToolCallMessages())
                .todayMessages(chatMessageMapper.countSince(todayStart))
                .activeUsers7d(chatMessageMapper.countDistinctUsersSince(weekAgo))
                .build();

        List<DailyPointBuilder> daily = buildDaily(since, today);

        List<UsageStatsVo.TopUser> topUsers = chatMessageMapper.topUsersSince(weekAgo).stream()
                .map(r -> {
                    Number userId = (Number) r.get("userId");
                    String username = (String) r.get("username");
                    return UsageStatsVo.TopUser.builder()
                            .userId(userId == null ? null : userId.longValue())
                            .username(username == null ? ("#" + userId) : username)
                            .sessions(toLong(r.get("s")))
                            .messages(toLong(r.get("c")))
                            .build();
                })
                .toList();

        UsageStatsVo vo = UsageStatsVo.builder()
                .overview(overview)
                .daily(daily.stream().map(d -> UsageStatsVo.DailyPoint.builder()
                        .date(d.date)
                        .messages(d.messages)
                        .users(d.users)
                        .build()).toList())
                .topUsers(topUsers)
                .build();
        cache.put(key, vo);
        return vo;
    }

    /** 生成 n 天序列;有数据的日期回填,其余补零。 */
    private List<DailyPointBuilder> buildDaily(LocalDateTime since, LocalDate today) {
        Map<String, DailyPointBuilder> map = new LinkedHashMap<>();
        LocalDate start = since.toLocalDate();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            map.put(d.toString(), new DailyPointBuilder(d.toString()));
        }
        for (Map<String, Object> row : chatMessageMapper.dailyStats(since)) {
            String d = row.get("d") == null ? null : row.get("d").toString();
            DailyPointBuilder b = map.get(d);
            if (b != null) {
                b.messages = toLong(row.get("c"));
                b.users = toLong(row.get("u"));
            }
        }
        return new ArrayList<>(map.values());
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static class DailyPointBuilder {
        final String date;
        long messages;
        long users;

        DailyPointBuilder(String date) {
            this.date = date;
        }
    }
}
