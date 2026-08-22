package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSessionRequest {
    private String title;
    /** 会话勾选启用的自定义技能名 */
    private java.util.List<String> skills;
    private String agent;
    private String providerId;
    private String modelId;
}
