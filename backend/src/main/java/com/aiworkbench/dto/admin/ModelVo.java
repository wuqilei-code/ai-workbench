package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModelVo {
    private Long id;
    private String providerId;
    private String modelId;
    private String name;
    private Integer enabled;
    private Integer sortOrder;
}
