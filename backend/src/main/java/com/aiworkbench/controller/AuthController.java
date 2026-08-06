package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.auth.AuthResponse;
import com.aiworkbench.dto.auth.LoginRequest;
import com.aiworkbench.dto.auth.RegisterRequest;
import com.aiworkbench.dto.auth.UserVo;
import com.aiworkbench.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    @AuditLog(action = "注册")
    public R<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(userService.register(req));
    }

    @PostMapping("/login")
    @AuditLog(action = "登录")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(userService.login(req));
    }

    @GetMapping("/me")
    public R<UserVo> me() {
        return R.ok(userService.currentUser(SecurityUtil.currentUserId()));
    }
}
