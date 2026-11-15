package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型调用请求/响应原文(gzip,按 sha256 内容寻址去重)。 */
@Data
@TableName("llm_blob")
public class LlmBlob {

    /** sha256(hex) */
    @TableId
    private String hash;

    /** gzip 压缩后的 JSON 文本 */
    private byte[] data;

    private Integer size;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
