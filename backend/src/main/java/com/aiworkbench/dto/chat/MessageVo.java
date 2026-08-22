package com.aiworkbench.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MessageVo {
    private Long id;
    /** 执行轨道的外部消息 id(deepagents 合成 "da-msg-..." 前缀) */
    private String agentMessageId;
    private String role;
    private String content;
    /** 结构化 parts:{toolCalls:[{id,name,args,output,status}]}。旧消息为 null(non_null 不输出) */
    private JsonNode parts;
    /** 1=压缩产生的总结消息(前端不展示,仅占位;deepagents 轨道恒为 0) */
    private boolean compactionSummary;
    private String status;
    private LocalDateTime createdAt;
}
