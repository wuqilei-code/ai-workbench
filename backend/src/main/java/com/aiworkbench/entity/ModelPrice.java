package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型单价表:按模型估算 token 成本(每 1M token 价格,元)。
 */
@Data
@TableName("model_price")
public class ModelPrice {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerId;
    private String modelId;
    private String name;

    /** 每 1M 输入 token 价格(元) */
    private BigDecimal inputPrice;
    /** 每 1M 输出 token 价格(元) */
    private BigDecimal outputPrice;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
