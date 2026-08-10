package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.config.JwtProperties;
import com.aiworkbench.dto.auth.AuthResponse;
import com.aiworkbench.dto.auth.LoginRequest;
import com.aiworkbench.dto.auth.RegisterRequest;
import com.aiworkbench.dto.auth.UserVo;
import com.aiworkbench.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
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

    public static final String TOKEN_COOKIE = "aiwb_token";

    private final UserService userService;
    private final JwtProperties jwtProperties;

    @PostMapping("/register")
    @AuditLog(action = "注册")
    public R<AuthResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletResponse resp) {
        return R.ok(withCookie(resp, userService.register(req)));
    }

    @PostMapping("/login")
    @AuditLog(action = "登录")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse resp) {
        return R.ok(withCookie(resp, userService.login(req)));
    }

    @GetMapping("/me")
    public R<UserVo> me() {
        return R.ok(userService.currentUser(SecurityUtil.currentUserId()));
    }

    /** 登录/注册成功后将 JWT 写入 cookie,供预览 iframe 及其子资源请求(不带 header)自动鉴权。 */
    private AuthResponse withCookie(HttpServletResponse resp, AuthResponse auth) {
        if (auth != null && auth.getToken() != null && !auth.getToken().isBlank()) {
            Cookie cookie = new Cookie(TOKEN_COOKIE, auth.getToken());
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge((int) (jwtProperties.getTtlMinutes() * 60L)); // 与 JWT 有效期对齐
            cookie.setAttribute("SameSite", "Lax");
            resp.addCookie(cookie);
        }
        return auth;
    }
}
