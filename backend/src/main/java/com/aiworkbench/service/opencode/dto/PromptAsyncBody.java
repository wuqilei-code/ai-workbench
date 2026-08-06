package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * POST /session/{id}/prompt_async 请求体。
 * 注意:prompt_async 的 model 字段使用 {providerID, modelID}(与 create session 的 {id, providerID} 不同)。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromptAsyncBody {
    private List<Part> parts;
    private ModelRef model;
    private String agent;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Part {
        private String type;
        private String text;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelRef {
        @JsonProperty("providerID")
        private String providerID;
        @JsonProperty("modelID")
        private String modelID;
    }
}
