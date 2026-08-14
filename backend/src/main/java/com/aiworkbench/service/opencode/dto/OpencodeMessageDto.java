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
    /** 消息模式;compaction agent 生成的总结消息为 "compaction" */
    private String mode;
    /** 压缩总结标记(compaction agent 生成的 assistant 消息为 true) */
    private Boolean summary;
    /** 最近一次请求的 token 用量(assistant 消息;输入含缓存/推理)。 */
    private Tokens tokens;
    /** REST /message 列表接口的元信息嵌套在 info 节点里(SSE 事件则是扁平结构)。 */
    @JsonProperty("info")
    private JsonNode info;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tokens {
        private Long input;
        private Long output;
        private Long reasoning;
        private Cache cache;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Cache {
            private Long read;
            private Long write;
        }
    }

    public String getRole() {
        if (role != null) {
            return role;
        }
        return info != null ? info.path("role").asText(null) : null;
    }

    public JsonNode getParts() {
        return parts;
    }

    public String getSessionID() {
        if (sessionID != null) {
            return sessionID;
        }
        return info != null ? info.path("sessionID").asText(null) : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Time {
        private Long created;
        private Long completed;
    }
}
