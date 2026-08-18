package com.aiworkbench.dto.browser;

import lombok.Data;

import java.util.Map;

/** 浏览器面板接管动作请求:tool 为 playwright-mcp 工具名,args 为工具参数。 */
@Data
public class BrowserActionRequest {
    private String tool;
    private Map<String, Object> args;
}
