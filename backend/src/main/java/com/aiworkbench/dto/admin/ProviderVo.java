package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProviderVo {
    private Long id;
    private String providerId;
    private String name;
    private String baseUrl;
    /** opencode 自定义 provider 的 AI SDK npm 包名 */
    private String npm;
    /** 列表返回时掩码,不回显明文 */
    private String apiKeyMasked;
    private String envVarName;
    private Integer enabled;
    private Integer sortOrder;
    private List<ModelVo> models;
}
