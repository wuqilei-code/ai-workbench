package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * opencode Message 对象(UserMessage | AssistantMessage)。
 * parts 为原始 JSON,保真存储;文本提取在 ChatMessageService 完成。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpencodeMessageDto {
    private String id;
    @JsonProperty("sessionID")
    private String sessionID;
    /** user | assistant */
    private String role;
    private JsonNode parts;
    private Time time;
    @JsonProperty("providerID")
    private String providerID;
    @JsonProperty("modelID")
    private String modelID;
    private String agent;
    private String finish;
    private String error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Time {
        private Long created;
        private Long completed;
    }
}
