package com.aiworkbench.mapper;

import com.aiworkbench.entity.ChatSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Select("select count(*) from chat_session")
    long countTotal();
}
