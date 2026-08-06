package com.aiworkbench.service.opencode.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * opencode GET /global/health 响应:{healthy, version}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HealthDto {
    private Boolean healthy;
    private String version;
}
