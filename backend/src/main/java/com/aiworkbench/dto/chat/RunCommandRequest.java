package com.aiworkbench.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/chat/sessions/{id}/commands 请求体:触发 slash 命令。 */
@Data
public class RunCommandRequest {
    @NotBlank(message = "命令不能为空")
    private String command;

    /** 命令参数(/cmd 后的剩余文本),可为空 */
    @Size(max = 32000, message = "参数过长")
    private String arguments;
}
