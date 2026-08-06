package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 32000, message = "消息过长")
    private String text;

    /** 可选:覆盖会话默认模型 */
    private String providerId;
    private String modelId;
}
