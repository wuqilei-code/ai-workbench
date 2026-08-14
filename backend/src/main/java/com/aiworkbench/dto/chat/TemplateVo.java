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
    /** emoji 图标 */
    private String icon;
    /** true=系统内置(只读,但可启停) */
    private boolean builtin;
    /** 当前用户是否已启用(在首页展示) */
    private boolean enabled;
    private LocalDateTime updatedAt;
}
