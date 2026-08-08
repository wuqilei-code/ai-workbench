package com.aiworkbench.mapper;

import com.aiworkbench.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("select count(*) from chat_message")
    long countTotal();

    /** 含 toolCalls 的消息数(近似工具调用量;assistant 消息的 parts 才会记录) */
    @Select("select count(*) from chat_message where role = 'assistant' and parts like '%toolCalls%'")
    long countToolCallMessages();

    @Select("select count(*) from chat_message where created_at >= #{since}")
    long countSince(@Param("since") LocalDateTime since);

    @Select("select count(distinct user_id) from chat_message where created_at >= #{since}")
    long countDistinctUsersSince(@Param("since") LocalDateTime since);

    @Select("select date(created_at) as d, count(*) as c, count(distinct user_id) as u " +
            "from chat_message where created_at >= #{since} group by date(created_at) order by d")
    List<Map<String, Object>> dailyStats(@Param("since") LocalDateTime since);

    @Select("select m.user_id as userId, u.username as username, count(*) as c, count(distinct m.session_id) as s " +
            "from chat_message m join users u on u.id = m.user_id " +
            "where m.created_at >= #{since} group by m.user_id, u.username order by c desc limit 10")
    List<Map<String, Object>> topUsersSince(@Param("since") LocalDateTime since);
}
