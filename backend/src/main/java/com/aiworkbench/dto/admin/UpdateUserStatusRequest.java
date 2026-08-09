package com.aiworkbench.dto.admin;

import lombok.Data;

/** 管理端启用/禁用用户请求。 */
@Data
public class UpdateUserStatusRequest {
    /** 1=启用, 0=禁用 */
    private Integer status;
}
