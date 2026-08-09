package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能安装记录:用户从广场安装技能。同一用户对同一技能名不可重复安装(唯一约束)。
 */
@Data
@TableName("skill_install")
public class SkillInstall {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 对应 skill_marketplace.id */
    private Long skillId;

    /** 冗余技能名,便于查重 */
    private String skillName;

    private LocalDateTime installedAt;
}
