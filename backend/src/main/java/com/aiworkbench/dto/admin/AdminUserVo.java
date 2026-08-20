package com.aiworkbench.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端用户列表项(含状态/uid/时间戳,不回显密码)。 */
@Data
@Builder
public class AdminUserVo {
    private Long id;
    private String username;
    private String email;
    private String role;
    /** 1=active, 0=disabled */
    private Integer status;
    /** per-user 隔离 uid;未分配时为 null */
    private Integer uid;
    /** agent 运行时偏好(opencode|dsh|null=全局默认);null 时前端显示“跟随全局” */
    private String agentRuntime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
