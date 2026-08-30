package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class McpVo {
    private Long id;
    private Long userId;
    private String name;
    private String type;
    private String command;
    private String url;
    private String headers;
    private String config;
    private String environment;
    private Integer enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}