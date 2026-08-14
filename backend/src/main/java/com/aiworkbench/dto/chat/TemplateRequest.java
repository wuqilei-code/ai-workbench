package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建/更新提示词模板。系统内置模板(id 关联 user_id 为 NULL 的)不可编辑。 */
@Data
public class TemplateRequest {
    @NotBlank
    @Size(max = 128)
    private String title;

    @NotBlank
    @Size(max = 32)
    private String category;

    @NotBlank
    @Size(max = 8000)
    private String content;

    /** emoji 图标(可空,默认 📋) */
    @Size(max = 8)
    private String icon;
}
