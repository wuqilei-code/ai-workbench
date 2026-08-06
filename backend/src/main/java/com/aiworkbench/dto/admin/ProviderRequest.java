package com.aiworkbench.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProviderRequest {
    @NotBlank
    private String providerId;
    @NotBlank
    @Size(max = 128)
    private String name;
    @Size(max = 512)
    private String baseUrl;
    /** opencode 自定义 provider 的 AI SDK npm 包名,如 @ai-sdk/openai-compatible;内置 provider 可空 */
    @Size(max = 128)
    private String npm;
    /** 为空表示不修改;显式传空串表示清空。Phase 7 起 AES-GCM 加密落库(密文带 enc: 前缀) */
    @Size(max = 512)
    private String apiKey;
    @Size(max = 64)
    private String envVarName;
    private Integer enabled;
    private Integer sortOrder;
}
