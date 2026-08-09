package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 提示词模板项:内置(isBuiltin=true)与个人自建统一返回;内置不可编辑/删除。 */
@Data
@Builder
public class TemplateVo {
    private Long id;
    private String title;
    private String category;
    private String content;
    /** true=系统内置(只读) */
    private boolean builtin;
    private LocalDateTime updatedAt;
}
