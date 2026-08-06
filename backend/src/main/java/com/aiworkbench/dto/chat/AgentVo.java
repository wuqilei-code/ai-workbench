package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

/** 前端 agent 选择项(opencode GET /agent 的裁剪:过滤 hidden 与 subagent)。 */
@Data
@Builder
public class AgentVo {
    private String name;
    private String description;
    private String mode;
}
