package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户技能(自建/从市场安装;私有/公开)。 */
@Data
@TableName("user_skill")
public class UserSkill {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 技能名(与 skills/<name>/SKILL.md 目录一致) */
    private String name;

    private String description;

    /** SKILL.md 正文(含 frontmatter) */
    private String content;

    /** 附加文件 JSON:[{path,content}] */
    private String files;

    /** 0=私有 1=公开(公开进技能市场) */
    private Integer visibility;

    /** self=自建 installed=从市场安装 */
    private String source;

    /** 安装来源技能 id */
    private Long sourceSkillId;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}