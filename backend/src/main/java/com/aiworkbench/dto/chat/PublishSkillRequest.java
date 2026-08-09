package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发布技能到广场:在线填写 SKILL.md 内容。 */
@Data
public class PublishSkillRequest {
    @NotBlank
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "技能名需为小写字母/数字,单词用连字符分隔")
    @Size(max = 64)
    private String name;

    @NotBlank
    @Size(max = 1024)
    private String description;

    /** SKILL.md 全文(需含 frontmatter:name/description) */
    @NotBlank
    @Size(max = 60000)
    private String content;
}
