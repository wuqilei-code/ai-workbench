package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板。user_id 为 NULL 表示系统内置(全员只读);否则为个人自建模板。
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** NULL = 系统内置 */
    private Long userId;

    /** self=自建;installed=市场安装(只读) */
    private String source;

    private String title;
    private String category;
    /** 模板介绍(文档型模板用于详情页展示) */
    private String description;
    /** 标签(逗号分隔,详情页展示) */
    private String tags;
    private String content;
    /** prompt=提示词模板(默认);html=HTML 文档样板 */
    private String format;
    /** 关联技能名(文档型模板用,如 html-report) */
    private String skill;
    /** 1=精选(首页/精选 Tab) */
    private Integer featured;
    /** 缩略图 URL(可空;空则前端用 live 渲染) */
    private String thumb;
    /** emoji 图标(首页卡片展示) */
    private String icon;
    private Integer sortOrder;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
