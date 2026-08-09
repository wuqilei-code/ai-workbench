package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能广场发布的技能。用户把自建技能发布到广场,其他用户可浏览并安装到自己的工作区。
 */
@Data
@TableName("skill_marketplace")
public class SkillMarketplace {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布者 */
    private Long userId;

    /** 发布者用户名(冗余展示) */
    private String authorName;

    /** 技能名(小写 kebab-case) */
    private String name;

    /** 一句话描述 */
    private String description;

    /** SKILL.md 全文 */
    private String content;

    /** 被安装次数 */
    private Integer installs;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
