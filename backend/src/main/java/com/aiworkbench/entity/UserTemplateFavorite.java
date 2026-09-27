package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户收藏的模板关系表。 */
@Data
@TableName("user_template_favorite")
public class UserTemplateFavorite {
    private Long userId;
    private Long templateId;
    private LocalDateTime createdAt;
}
