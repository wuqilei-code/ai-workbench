package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户模板启用关系:用户把内置/自建模板添加到自己的首页。
 */
@Data
@TableName("user_template")
public class UserTemplate {
    private Long userId;
    private Long templateId;
    private Integer enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
