package com.aiworkbench.dto.skill;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class UserSkillRequest {
    @NotBlank(message = "技能名不能为空")
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private String content;

    /** 0=私有 1=公开 */
    private Integer visibility;

    /** 附加文件(SKILL.md 之外的脚本/数据/参考等,path 相对技能目录) */
    private java.util.List<com.aiworkbench.dto.chat.SkillFileVo> files;
}