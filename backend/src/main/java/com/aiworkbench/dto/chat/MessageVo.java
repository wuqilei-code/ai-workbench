package com.aiworkbench.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MessageVo {
    private Long id;
    private String opencodeMessageId;
    private String role;
    private String content;
    /** 结构化 part:{toolCalls:[...], stepFinish:{reason}}。旧消息为 null(non_null 不输出) */
    private JsonNode parts;
    private String status;
    private LocalDateTime createdAt;
}
