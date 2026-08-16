package com.aiworkbench.service;

import com.aiworkbench.dto.my.MyStatsVo;
import com.aiworkbench.entity.ModelPrice;
import com.aiworkbench.entity.UserDailyMetric;
import com.aiworkbench.mapper.ModelPriceMapper;
import com.aiworkbench.mapper.MyStatsMapper;
import com.aiworkbench.mapper.UserDailyMetricMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个人驾驶舱聚合服务。
 * <ul>
 *   <li>生命周期总量直接用 chat_message 的方案 A 数值列 SUM(免解析 JSON)。</li>
 *   <li>每日趋势读 user_daily_metrics(方案 B 汇总表);缺失日期与今天实时 rollup。</li>
 *   <li>按模型 token 用冗余 model_id 聚合,成本按 model_price 估算。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyStatsService {

    private final MyStatsMapper myStatsMapper;
    private final UserDailyMetricMapper userDailyMetricMapper;
    private final ModelPriceMapper modelPriceMapper;

    public MyStatsVo dashboard(Long userId, int days) {
        int n = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(n - 1L);

        List<MyStatsVo.DailyPoint> daily = buildDaily(userId, since, today);

        return MyStatsVo.builder()
                .overview(buildOverview(userId, daily))
                .daily(daily)
                .agents(toNamedStats(myStatsMapper.agentDistribution(userId), "agent"))
                .models(buildModelStats(userId))
                .build();
    }

    private MyStatsVo.Overview buildOverview(Long userId, List<MyStatsVo.DailyPoint> daily) {
        Map<String, Object> life = myStatsMapper.lifetime(userId);
        Map<String, Object> sessions = myStatsMapper.totalSessions(userId);
        Map<String, Object> jobs = myStatsMapper.jobStats(userId);

        long totalMessages = toLong(life.get("messages"));
        long totalSessions = toLong(sessions.get("cnt"));

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> week = myStatsMapper.rangeStats(userId, now.minusDays(7), now);
        Map<String, Object> prevWeek = myStatsMapper.rangeStats(userId, now.minusDays(14), now.minusDays(7));

        List<MyStatsVo.ModelStat> models = buildModelStats(userId);
        double totalCost = models.stream().mapToDouble(MyStatsVo.ModelStat::getEstimatedCost).sum();

        return MyStatsVo.Overview.builder()
                .totalSessions(totalSessions)
                .totalMessages(totalMessages)
                .userMessages(toLong(life.get("userMessages")))
                .assistantMessages(toLong(life.get("assistantMessages")))
                .inputTokens(toLong(life.get("inputTokens")))
                .outputTokens(toLong(life.get("outputTokens")))
                .reasoningTokens(toLong(life.get("reasoningTokens")))
                .cacheReadTokens(toLong(life.get("cacheReadTokens")))
                .cacheWriteTokens(toLong(life.get("cacheWriteTokens")))
                .toolCalls(toLong(life.get("toolCalls")))
                .filesChanged(toLong(life.get("filesChanged")))
                .compactionCount(myStatsMapper.compactionCount(userId))
                .unreadNotifications(myStatsMapper.unreadNotifications(userId))
                .avgMessagesPerSession(totalSessions == 0 ? 0 : (double) totalMessages / totalSessions)
                .activeDays(myStatsMapper.activeDays(userId))
                .currentStreak(currentStreak(daily))
                .weekMessages(toLong(week.get("messages")))
                .prevWeekMessages(toLong(prevWeek.get("messages")))
                .weekOutputTokens(toLong(week.get("outputTokens")))
                .prevWeekOutputTokens(toLong(prevWeek.get("outputTokens")))
                .totalEstimatedCost(totalCost)
                .totalTasks(toLong(jobs.get("totalTasks")))
                .enabledTasks(toLong(jobs.get("enabledTasks")))
                .totalRuns(toLong(jobs.get("totalRuns")))
                .successRuns(toLong(jobs.get("successRuns")))
                .failedRuns(toLong(jobs.get("failedRuns")))
                .installedSkills(myStatsMapper.installedSkills(userId))
                .publishedSkills(myStatsMapper.publishedSkills(userId))
                .publishedSkillInstalls(myStatsMapper.publishedSkillInstalls(userId))
                .privateMcps(myStatsMapper.privateMcps(userId))
                .myTemplates(myStatsMapper.myTemplates(userId))
                .favorites(myStatsMapper.favoritesCount(userId))
                .build();
    }

    /** 按模型合并:会话数(chat_session) + token(chat_message) + 估算成本(model_price)。 */
    private List<MyStatsVo.ModelStat> buildModelStats(Long userId) {
        Map<String, Long> sessionsByModel = new HashMap<>();
        for (Map<String, Object> r : myStatsMapper.modelDistribution(userId)) {
            String name = r.get("model") == null ? null : r.get("model").toString();
            if (name != null && !name.isBlank()) sessionsByModel.put(name, toLong(r.get("cnt")));
        }

        Map<String, BigDecimal> inputPrice = new HashMap<>();
        Map<String, BigDecimal> outputPrice = new HashMap<>();
        for (ModelPrice p : modelPriceMapper.selectList(null)) {
            if (p.getModelId() == null) continue;
            inputPrice.put(p.getModelId(), p.getInputPrice() == null ? BigDecimal.ZERO : p.getInputPrice());
            outputPrice.put(p.getModelId(), p.getOutputPrice() == null ? BigDecimal.ZERO : p.getOutputPrice());
        }

        Set<String> models = new HashSet<>(sessionsByModel.keySet());
        Map<String, long[]> tokenMap = new HashMap<>();
        for (Map<String, Object> r : myStatsMapper.modelTokens(userId)) {
            String name = r.get("model") == null ? null : r.get("model").toString();
            if (name == null) continue;
            tokenMap.put(name, new long[]{
                    toLong(r.get("inputTokens")), toLong(r.get("outputTokens")), toLong(r.get("reasoningTokens"))});
            models.add(name);
        }

        List<MyStatsVo.ModelStat> out = new ArrayList<>();
        for (String model : models) {
            long[] t = tokenMap.getOrDefault(model, new long[]{0, 0, 0});
            long sessions = sessionsByModel.getOrDefault(model, 0L);
            double cost = 0;
            if (inputPrice.containsKey(model) || outputPrice.containsKey(model)) {
                BigDecimal ip = inputPrice.getOrDefault(model, BigDecimal.ZERO);
                BigDecimal op = outputPrice.getOrDefault(model, BigDecimal.ZERO);
                // cost = input/1e6*ip + output/1e6*op
                cost = BigDecimal.valueOf(t[0]).multiply(ip)
                        .add(BigDecimal.valueOf(t[1]).multiply(op))
                        .divide(BigDecimal.valueOf(1_000_000L), 6, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
            }
            out.add(MyStatsVo.ModelStat.builder()
                    .model(model)
                    .sessions(sessions)
                    .inputTokens(t[0])
                    .outputTokens(t[1])
                    .reasoningTokens(t[2])
                    .estimatedCost(cost)
                    .build());
        }
        out.sort((a, b) -> Long.compare(
                b.getInputTokens() + b.getOutputTokens(), a.getInputTokens() + a.getOutputTokens()));
        return out;
    }

    /** 连续活跃天数:从今天往回数连续有消息的天数(限已 rollup 窗口内)。 */
    private long currentStreak(List<MyStatsVo.DailyPoint> daily) {
        long streak = 0;
        for (int i = daily.size() - 1; i >= 0; i--) {
            if (daily.get(i).getMessages() > 0) streak++;
            else if (i == daily.size() - 1) continue; // 今天可能还没消息,跳过今天继续数
            else break;
        }
        return streak;
    }

    /** 补齐 [since, today] 每日汇总(缺失日期 rollup,今天实时重算),再读取补零序列。 */
    private List<MyStatsVo.DailyPoint> buildDaily(Long userId, LocalDate since, LocalDate today) {
        Set<LocalDate> have = new HashSet<>();
        for (UserDailyMetric m : userDailyMetricMapper.selectList(
                new LambdaQueryWrapper<UserDailyMetric>()
                        .eq(UserDailyMetric::getUserId, userId)
                        .ge(UserDailyMetric::getStatDate, since)
                        .le(UserDailyMetric::getStatDate, today))) {
            have.add(m.getStatDate());
        }

        for (LocalDate d = since; !d.isAfter(today); d = d.plusDays(1)) {
            if (!have.contains(d) || d.equals(today)) {
                try {
                    rollupDay(userId, d);
                } catch (Exception e) {
                    log.warn("rollup failed (userId={}, date={}): {}", userId, d, e.toString());
                }
            }
        }

        Map<String, MyStatsVo.DailyPoint> map = new LinkedHashMap<>();
        for (LocalDate d = since; !d.isAfter(today); d = d.plusDays(1)) {
            map.put(d.toString(), MyStatsVo.DailyPoint.builder()
                    .date(d.toString()).messages(0).inputTokens(0).outputTokens(0)
                    .toolCalls(0).sessions(0).filesChanged(0).build());
        }
        for (UserDailyMetric m : userDailyMetricMapper.selectList(
                new LambdaQueryWrapper<UserDailyMetric>()
                        .eq(UserDailyMetric::getUserId, userId)
                        .ge(UserDailyMetric::getStatDate, since)
                        .le(UserDailyMetric::getStatDate, today)
                        .orderByAsc(UserDailyMetric::getStatDate))) {
            MyStatsVo.DailyPoint p = map.get(m.getStatDate().toString());
            if (p != null) {
                p.setMessages(nz(m.getMessages()));
                p.setInputTokens(nz(m.getInputTokens()));
                p.setOutputTokens(nz(m.getOutputTokens()));
                p.setToolCalls(nz(m.getToolCalls()));
                p.setSessions(nz(m.getSessions()));
                p.setFilesChanged(nz(m.getFilesChanged()));
            }
        }
        return new ArrayList<>(map.values());
    }

    private void rollupDay(Long userId, LocalDate date) {
        userDailyMetricMapper.rollup(userId, date, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    private List<MyStatsVo.NamedStat> toNamedStats(List<Map<String, Object>> rows, String nameKey) {
        List<MyStatsVo.NamedStat> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String name = r.get(nameKey) == null ? null : r.get(nameKey).toString();
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(MyStatsVo.NamedStat.builder().name(name).count(toLong(r.get("cnt"))).build());
        }
        return out;
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
