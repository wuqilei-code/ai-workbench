package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * opencode Session 对象(POST /session 响应、GET /session 列表项)。
 * 字段名严格对齐 OpenCode v2 API(camelCase: providerID)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpencodeSessionDto {
    private String id;
    private String title;
    private String agent;
    private String directory;
    private ModelRef model;
    private Time time;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelRef {
        /** session 上的 model 字段用 id(非 modelID) */
        @JsonProperty("id")
        private String id;
        @JsonProperty("providerID")
        private String providerID;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Time {
        private Long created;
        private Long updated;
        private Long archived;
    }
}
