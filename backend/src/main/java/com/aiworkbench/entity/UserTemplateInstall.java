package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模板安装关系:用户从市场安装到自己的模板库。 */
@Data
@TableName("user_template_install")
public class UserTemplateInstall {
    private Long userId;
    private Long marketplaceId;
    private Long templateId;
    private LocalDateTime createdAt;
}
