package com.aiworkbench.dto.admin;

import lombok.Data;

/** 管理端设置用户 agent 运行时请求(null 清除,恢复全局默认;合法值 opencode | dsh)。 */
@Data
public class UpdateUserAgentRuntimeRequest {
    private String agentRuntime;
}