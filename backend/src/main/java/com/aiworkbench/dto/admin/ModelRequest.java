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
}
