package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** GET /api/models 返回结构:按 provider 分组的可用模型 */
@Data
@Builder
public class ModelGroupVo {
    private String providerId;
    private String name;
    private List<ModelOption> models;

    @Data
    @Builder
    public static class ModelOption {
        private String modelId;
        private String name;
    }
}
