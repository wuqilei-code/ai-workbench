package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("provider_config")
public class ProviderConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑 id,如 openai / ollama / vllm-local */
    private String providerId;
    private String name;
    /** OpenAI 兼容端点(自定义 provider 用) */
    private String baseUrl;
    /** opencode 自定义 provider 的 AI SDK npm 包名,如 @ai-sdk/openai-compatible;内置 provider 可空 */
    private String npm;
    /** API key(AES-GCM 加密落库,密文带 enc: 前缀) */
    private String apiKey;
    /** opencode 期望的 env 变量名,如 OPENAI_API_KEY */
    private String envVarName;
    private Integer enabled;
    private Integer sortOrder;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
