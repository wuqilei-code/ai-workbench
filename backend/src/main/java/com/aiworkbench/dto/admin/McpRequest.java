package com.aiworkbench.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** type 固定 remote(HTTP/SSE URL);url 必填,headers 可选(JSON)。 */
@Data
public class McpRequest {
    /** 0=管理员公共配置;用户私有配置时由服务端强制设为当前用户 id,忽略此字段 */
    private Long userId;

    @NotBlank
    @Size(max = 64)
    private String name;

    /** remote(HTTP/SSE URL),仅支持远程 MCP */
    @NotBlank
    private String type;

    /** remote 模式的 URL,必填 */
    @Size(max = 512)
    private String url;

    /** remote 鉴权头(JSON:{key:value}) */
    private String headers;

    private Integer enabled;
    private Integer sortOrder;
}