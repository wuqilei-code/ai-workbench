package com.aiworkbench.mapper;

import com.aiworkbench.entity.SessionEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 会话 SSE 事件出站表访问(跨副本广播)。 */
@Mapper
public interface SessionEventMapper extends BaseMapper<SessionEvent> {
}
