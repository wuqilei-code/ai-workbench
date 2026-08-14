package com.aiworkbench.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ModelRequest {
    @NotBlank
    private String providerId;
    @NotBlank
    private String modelId;
    @NotBlank
    private String name;
    private Integer enabled;
    private Integer sortOrder;
    /** 模型上下文窗口 token 上限(进度环用;不填则前端估算兜底) */
    private Integer contextLimit;
}
