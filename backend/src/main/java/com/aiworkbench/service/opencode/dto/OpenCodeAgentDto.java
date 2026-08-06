package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * GET /agent 返回的 agent 项(1.18.x):{name, description, mode(subagent|primary|all), hidden, model, prompt, ...}。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenCodeAgentDto {
    private String name;
    private String description;
    private String mode;
    private Boolean hidden;
    private ModelRef model;

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
