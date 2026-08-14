package com.aiworkbench.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP Server 配置。user_id=0 为管理员公共配置(所有用户共享),user_id>0 为用户私有配置(仅该用户生效)。
 * 仅支持 remote(HTTP/SSE URL)类型;local(命令进程)已废弃,渲染时忽略。
 * 渲染 opencode.json 的 mcp 段时,公共配置叠加该用户私有配置。
 */
@Data
@TableName("mcp_config")
public class McpConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0=管理员公共配置,>0=用户私有配置 */
    private Long userId;
    /** MCP server 标识(remote 服务名),小写 kebab-case,同用户内唯一 */
    private String name;
    /** remote(HTTP/SSE URL);local 已废弃 */
    private String type;
    /** 已废弃(local 用),仅保留兼容 */
    private String command;
    /** remote 模式的服务端点 URL */
    private String url;
    /** remote 鉴权头(JSON:{key:value}) */
    private String headers;
    /** 已废弃(local 用),仅保留兼容 */
    private String environment;
    private Integer enabled;
    private Integer sortOrder;

    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}