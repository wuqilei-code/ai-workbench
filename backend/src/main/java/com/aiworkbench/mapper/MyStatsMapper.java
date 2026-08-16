package com.aiworkbench.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 个人驾驶舱聚合查询(方案 A 数值列 + 各类计数)。
 * 不依赖运行时解析 JSON:token 用抽取的数值列,文件变动用 JSON_LENGTH(summaryFiles)。
 */
public interface MyStatsMapper {

    /** 生命周期汇总(当前用户):消息数 / token / 工具调用 / 文件变动。 */
    @Select("SELECT " +
            " SUM(CASE WHEN role IN ('user','assistant') AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END) AS messages, " +
            " SUM(CASE WHEN role = 'user' AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END) AS userMessages, " +
            " SUM(CASE WHEN role = 'assistant' AND (compaction_summary IS NULL OR compaction_summary = 0) THEN 1 ELSE 0 END) AS assistantMessages, " +
            " COALESCE(SUM(input_tokens), 0) AS inputTokens, " +
            " COALESCE(SUM(output_tokens), 0) AS outputTokens, " +
            " COALESCE(SUM(reasoning_tokens), 0) AS reasoningTokens, " +
            " COALESCE(SUM(cache_read_tokens), 0) AS cacheReadTokens, " +
            " COALESCE(SUM(cache_write_tokens), 0) AS cacheWriteTokens, " +
            " COALESCE(SUM(COALESCE(tool_call_count, 0)), 0) AS toolCalls, " +
            " COALESCE(SUM(JSON_LENGTH(JSON_EXTRACT(parts, '$.summaryFiles'))), 0) AS filesChanged " +
            "FROM chat_message WHERE user_id = #{userId}")
    Map<String, Object> lifetime(@Param("userId") Long userId);

    @Select("SELECT COALESCE(COUNT(*), 0) AS cnt FROM chat_session WHERE user_id = #{userId}")
    Map<String, Object> totalSessions(@Param("userId") Long userId);

    /** agent 分布(会话数)。 */
    @Select("SELECT agent AS agent, COUNT(*) AS cnt FROM chat_session WHERE user_id = #{userId} GROUP BY agent ORDER BY cnt DESC")
    List<Map<String, Object>> agentDistribution(@Param("userId") Long userId);

    /** 模型分布(会话数,按 model_id)。 */
    @Select("SELECT model_id AS model, COUNT(*) AS cnt FROM chat_session WHERE user_id = #{userId} AND model_id IS NOT NULL AND model_id <> '' GROUP BY model_id ORDER BY cnt DESC")
    List<Map<String, Object>> modelDistribution(@Param("userId") Long userId);

    /** 按模型 token 消耗(生命周期,从冗余 model_id 的 chat_message 聚合)。 */
    @Select("SELECT COALESCE(model_id, '(未指定)') AS model, " +
            " COALESCE(SUM(input_tokens), 0) AS inputTokens, " +
            " COALESCE(SUM(output_tokens), 0) AS outputTokens, " +
            " COALESCE(SUM(reasoning_tokens), 0) AS reasoningTokens " +
            "FROM chat_message WHERE user_id = #{userId} GROUP BY model_id " +
            "ORDER BY (COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0)) DESC")
    List<Map<String, Object>> modelTokens(@Param("userId") Long userId);

    /** 压缩次数(compaction_summary=1)。 */
    @Select("SELECT COUNT(*) FROM chat_message WHERE user_id = #{userId} AND compaction_summary = 1")
    long compactionCount(@Param("userId") Long userId);

    /** 未读通知数。 */
    @Select("SELECT COUNT(*) FROM notification WHERE user_id = #{userId} AND read_flag = 0")
    long unreadNotifications(@Param("userId") Long userId);

    /** 累计活跃天数(有消息的自然日数)。 */
    @Select("SELECT COUNT(DISTINCT DATE(created_at)) FROM chat_message WHERE user_id = #{userId}")
    long activeDays(@Param("userId") Long userId);

    /** 某时间段内的消息数与输出 token(环比用)。 */
    @Select("SELECT COALESCE(COUNT(*), 0) AS messages, COALESCE(SUM(output_tokens), 0) AS outputTokens " +
            "FROM chat_message WHERE user_id = #{userId} AND created_at >= #{start} AND created_at < #{end}")
    Map<String, Object> rangeStats(@Param("userId") Long userId,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM favorite WHERE user_id = #{userId}")
    long favoritesCount(@Param("userId") Long userId);

    /** 定时任务统计。 */
    @Select("SELECT " +
            " (SELECT COUNT(*) FROM job_task WHERE user_id = #{userId}) AS totalTasks, " +
            " (SELECT COUNT(*) FROM job_task WHERE user_id = #{userId} AND enabled = 1) AS enabledTasks, " +
            " (SELECT COUNT(*) FROM job_task_run WHERE user_id = #{userId}) AS totalRuns, " +
            " (SELECT COUNT(*) FROM job_task_run WHERE user_id = #{userId} AND status = 'success') AS successRuns, " +
            " (SELECT COUNT(*) FROM job_task_run WHERE user_id = #{userId} AND status IN ('failed','timeout','cancelled')) AS failedRuns")
    Map<String, Object> jobStats(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM skill_install WHERE user_id = #{userId}")
    long installedSkills(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM skill_marketplace WHERE user_id = #{userId}")
    long publishedSkills(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(installs), 0) FROM skill_marketplace WHERE user_id = #{userId}")
    long publishedSkillInstalls(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM mcp_config WHERE user_id = #{userId}")
    long privateMcps(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM prompt_template WHERE user_id = #{userId}")
    long myTemplates(@Param("userId") Long userId);
}
