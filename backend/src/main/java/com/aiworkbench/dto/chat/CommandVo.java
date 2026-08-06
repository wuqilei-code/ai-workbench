package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

/** 前端 slash 命令菜单项(opencode GET /command 的裁剪:仅 source=command 的命令)。 */
@Data
@Builder
public class CommandVo {
    private String name;
    private String description;
    private String agent;
    private String model;
    private String source;
    private Boolean subtask;
    private java.util.List<String> hints;
}
