package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_config")
public class ModelConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerId;
    private String modelId;
    private String name;
    private Integer enabled;
    private Integer sortOrder;
    /** 模型上下文窗口 token 上限(进度环使用;空则前端用估算兜底) */
    private Integer contextLimit;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
