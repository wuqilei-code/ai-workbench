package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * POST /session 请求体。
 * 注意:create session 的 model 字段使用 {id, providerID}(与 prompt_async 的 {providerID, modelID} 不同)。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateSessionBody {
    private String title;
    private String agent;
    private ModelRef model;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelRef {
        @JsonProperty("id")
        private String id;
        @JsonProperty("providerID")
        private String providerID;
    }
}
