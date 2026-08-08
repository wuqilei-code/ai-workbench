package com.aiworkbench.dto.chat;

import lombok.Builder;
import lombok.Data;

/** 技能信息项(工作区技能见文件面板;内置全局技能由本 DTO 只读列出)。 */
@Data
@Builder
public class SkillInfoVo {
    private String name;
    private String description;
}
