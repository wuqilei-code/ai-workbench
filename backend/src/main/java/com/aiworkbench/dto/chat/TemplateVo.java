package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板项:内置(isBuiltin=true)与个人自建统一返回;内置不可编辑/删除。
 * format=prompt 为提示词模板;"html" 等为文档型样板(带 description/tags/skill,可预览渲染)。
 */
@Data
@Builder
public class TemplateVo {
    private Long id;
    private String title;
    private String category;
    private String description;
    /** 标签(逗号分隔文本) */
    private String tags;
    private String content;
    private String format;
    /** 关联技能名(文档型模板用) */
    private String skill;
    private boolean featured;
    /** 缩略图 URL(可空) */
    private String thumb;
    /** emoji 图标 */
    private String icon;
    /** true=系统内置(只读,但可启停) */
    private boolean builtin;
    /** self=自建;installed=市场安装(只读,仅可查看/使用) */
    private String source;
    /** 当前用户是否已启用(在首页展示) */
    private boolean enabled;
    /** 当前用户是否已收藏 */
    private boolean favorited;
    /** 当前用户是否已置顶 */
    private boolean pinned;
    /** 置顶顺序(越小越靠前) */
    private Integer pinOrder;
    /** 置顶时间 */
    private LocalDateTime pinnedAt;
    private LocalDateTime updatedAt;
}
