package com.aiworkbench.dto.admin;

import lombok.Data;

/** 管理端重置用户密码请求。 */
@Data
public class ResetUserPasswordRequest {
    /** 新密码(明文,后端编码后落库) */
    private String password;
}
