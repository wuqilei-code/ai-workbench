package com.aiworkbench.dto.auth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserVo {
    private Long id;
    private String username;
    private String email;
    private String role;
}
