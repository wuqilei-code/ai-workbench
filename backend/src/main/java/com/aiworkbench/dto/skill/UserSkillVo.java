package com.aiworkbench.dto.skill;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserSkillVo {
    private Long id;
    private String name;
    private String description;
    private String content;
    /** 0=私有 1=公开 */
    private Integer visibility;
    /** self=自建 installed=从市场安装(installed 只读) */
    private String source;
    /** 附加文件(SKILL.md 之外) */
    private java.util.List<com.aiworkbench.dto.chat.SkillFileVo> files;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}