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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
