package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * opencode SSE /event 的 data 载荷:{id, type, properties}。
 * properties 结构因 type 而异,故用 JsonNode 保留。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpencodeEventDto {
    private String id;
    private String type;
    private JsonNode properties;
}
