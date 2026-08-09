package com.aiworkbench.dto.chat;

import lombok.Data;

/** 技能附加文件(SKILL.md 之外的脚本/配置/参考数据)。 */
@Data
public class SkillFileVo {
    /** 相对技能目录的路径,如 scripts/clean.py、data/schema.json */
    private String path;
    /** 文本内容(脚本/配置等文本文件;二进制暂不支持) */
    private String content;
}