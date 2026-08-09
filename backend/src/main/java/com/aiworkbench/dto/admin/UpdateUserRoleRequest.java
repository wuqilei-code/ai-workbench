package com.aiworkbench.dto.admin;

import lombok.Data;

/** 管理端修改用户角色请求。 */
@Data
public class UpdateUserRoleRequest {
    /** user | admin */
    private String role;
}
