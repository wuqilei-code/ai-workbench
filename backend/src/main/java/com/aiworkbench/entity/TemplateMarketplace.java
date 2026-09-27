package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模板市场:用户分享到市场的模板(HTML 文档 / 提示词)。 */
@Data
@TableName("template_marketplace")
public class TemplateMarketplace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String authorName;
    private String title;
    private String description;
    private String category;
    private String tags;
    private String content;
    /** html / prompt */
    private String format;
    private String skill;
    private String icon;
    private Integer installs;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
