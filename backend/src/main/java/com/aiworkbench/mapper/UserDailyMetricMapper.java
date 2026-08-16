package com.aiworkbench.mapper;

import com.aiworkbench.entity.UserDailyMetric;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户每日指标汇总表 Mapper。
 * rollup 用 INSERT ... SELECT ... ON DUPLICATE KEY UPDATE,按 (user_id, stat_date) 幂等 upsert。
 */
public interface UserDailyMetricMapper extends BaseMapper<UserDailyMetric> {

    /**
     * 聚合某用户某天的 chat_message 到汇总表(方案 B)。
     * 同一天重复执行时覆盖为最新值(当日数据持续更新)。
     */
    @Insert("INSERT INTO user_daily_metrics " +
            "(user_id, stat_date, messages, user_messages, assistant_messages, " +
            " input_tokens, output_tokens, reasoning_tokens, cache_read_tokens, cache_write_tokens, " +
            " tool_calls, sessions, files_changed) " +
            "SELECT #{userId}, #{date}, " +
            " COALESCE(SUM(CASE WHEN role IN ('user','assistant') AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END), 0), " +
            " COALESCE(SUM(CASE WHEN role = 'user' AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END), 0), " +
            " COALESCE(SUM(CASE WHEN role = 'assistant' AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END), 0), " +
            " COALESCE(SUM(input_tokens), 0), " +
            " COALESCE(SUM(output_tokens), 0), " +
            " COALESCE(SUM(reasoning_tokens), 0), " +
            " COALESCE(SUM(cache_read_tokens), 0), " +
            " COALESCE(SUM(cache_write_tokens), 0), " +
            " COALESCE(SUM(COALESCE(tool_call_count, 0)), 0), " +
            " COUNT(DISTINCT session_id), " +
            " COALESCE(SUM(JSON_LENGTH(JSON_EXTRACT(parts, '$.summaryFiles'))), 0) " +
            "FROM chat_message " +
            "WHERE user_id = #{userId} AND created_at >= #{start} AND created_at < #{end} " +
            "ON DUPLICATE KEY UPDATE " +
            " messages = VALUES(messages), " +
            " user_messages = VALUES(user_messages), " +
            " assistant_messages = VALUES(assistant_messages), " +
            " input_tokens = VALUES(input_tokens), " +
            " output_tokens = VALUES(output_tokens), " +
            " reasoning_tokens = VALUES(reasoning_tokens), " +
            " cache_read_tokens = VALUES(cache_read_tokens), " +
            " cache_write_tokens = VALUES(cache_write_tokens), " +
            " tool_calls = VALUES(tool_calls), " +
            " sessions = VALUES(sessions), " +
            " files_changed = VALUES(files_changed)")
    int rollup(@Param("userId") Long userId,
               @Param("date") LocalDate date,
               @Param("start") LocalDateTime start,
               @Param("end") LocalDateTime end);
}
