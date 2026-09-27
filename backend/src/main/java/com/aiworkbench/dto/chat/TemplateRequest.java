package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建/更新模板。系统内置模板(id 关联 user_id 为 NULL)不可编辑。 */
@Data
public class TemplateRequest {
    @NotBlank
    @Size(max = 128)
    private String title;

    @NotBlank
    @Size(max = 32)
    private String category;

    /** 模板介绍(可选) */
    @Size(max = 512)
    private String description;

    /** 标签(逗号分隔文本,可选) */
    @Size(max = 255)
    private String tags;

    @NotBlank
    @Size(max = 60000)
    private String content;

    /** prompt(默认) / html */
    @Size(max = 16)
    private String format;

    /** 关联技能名(可选) */
    @Size(max = 64)
    private String skill;

    /** 缩略图 URL(可选) */
    @Size(max = 512)
    private String thumb;

    /** emoji 图标(可空,默认 📋) */
    @Size(max = 8)
    private String icon;
}
