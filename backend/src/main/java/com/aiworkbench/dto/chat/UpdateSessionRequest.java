package com.aiworkbench.dto.chat;

import lombok.Data;

@Data
public class UpdateSessionRequest {
    private String title;
    private String providerId;
    private String modelId;
    private Integer archived;
}
