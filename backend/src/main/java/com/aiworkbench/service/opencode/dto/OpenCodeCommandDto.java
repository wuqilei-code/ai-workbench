package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * GET /command 返回的命令项(1.18.x):source 区分 builtin/user md("command")、MCP、skill。
 * <p>
 * 字段对应 opencode Command schema:{name, description, agent, model, source, template, subtask, hints}。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenCodeCommandDto {
    private String name;
    private String description;
    private String agent;
    private String model;
    private String source;
    private String template;
    private Boolean subtask;
    private List<String> hints;
}
