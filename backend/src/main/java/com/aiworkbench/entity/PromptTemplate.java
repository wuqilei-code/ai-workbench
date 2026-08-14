package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板。user_id 为 NULL 表示系统内置(全员只读);否则为个人自建模板。
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** NULL = 系统内置 */
    private Long userId;

    private String title;
    private String category;
    private String content;
    /** emoji 图标(首页卡片展示) */
    private String icon;
    private Integer sortOrder;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
