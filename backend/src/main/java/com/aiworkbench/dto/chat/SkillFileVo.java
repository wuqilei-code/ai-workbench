package com.aiworkbench.dto.chat;

import lombok.Data;

/** 技能附加文件(SKILL.md 之外的脚本/配置/参考数据)。 */
@Data
public class SkillFileVo {
    /** 相对技能目录的路径,如 scripts/clean.py、data/schema.json */
    private String path;
    /** 文件内容:encoding 为 base64 时是 Base64 编码的二进制;否则视为 UTF-8 文本 */
    private String content;
    /** 编码方式:空或 "text" 表示文本; "base64" 表示 content 是 Base64 编码的二进制数据 */
    private String encoding;
}