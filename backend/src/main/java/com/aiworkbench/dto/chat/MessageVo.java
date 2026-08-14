package com.aiworkbench.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class MessageVo {
    private Long id;
    private String opencodeMessageId;
    private String role;
    private String content;
    /** 结构化 part:{toolCalls:[...], stepFinish:{reason}}。旧消息为 null(non_null 不输出) */
    private JsonNode parts;
    /** assistant 消息真实 token 用量 {input,output,reasoning,cache:{read,write}};进度环用,无则 null */
    private JsonNode tokens;
    /** 1=压缩产生的总结消息(前端不展示,仅占位) */
    private boolean compactionSummary;
    private String status;
    private LocalDateTime createdAt;
    /** 仅 role=summary:本次快照 commit sha(历史重建总结卡片 + 跳转 diff 用) */
    private String summarySha;
    /** 仅 role=summary:该快照的父提交 sha,用于展示"本轮(parent→sha)变更" */
    private String summaryParentSha;
    /** 仅 role=summary:本轮变更文件清单 */
    private List<SummaryFileVo> summaryFiles;

    @Data
    @Builder
    public static class SummaryFileVo {
        private String status;
        private String path;
    }
}
