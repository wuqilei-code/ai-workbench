package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.auth.AuthResponse;
import com.aiworkbench.dto.auth.LoginRequest;
import com.aiworkbench.dto.auth.RegisterRequest;
import com.aiworkbench.dto.auth.UserVo;
import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.UserMapper;
import com.aiworkbench.security.JwtTokenProvider;
import com.aiworkbench.service.user.UserProvisioningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserProvisioningService userProvisioningService;

    @Value("${app.registration.enabled:true}")
    private boolean registrationEnabled;

    public AuthResponse register(RegisterRequest req) {
        if (!registrationEnabled) {
            throw new BusinessException(ResultCode.REGISTRATION_DISABLED);
        }
        // 唯一性校验
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) != null) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, req.getEmail())) != null) {
            throw new BusinessException(ResultCode.EMAIL_EXISTS);
        }
        // 首个注册用户自动成为 admin
        long count = userMapper.selectCount(null);
        String role = count == 0 ? "admin" : "user";

        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);

        // Phase 1:分配 uid + 创建 0700 私有工作目录(per-user 隔离地基)
        userProvisioningService.ensureProvisioned(user.getId());

        log.info("user registered: id={} username={} role={}", user.getId(), user.getUsername(), role);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsernameOrEmail())
                .or().eq(User::getEmail, req.getUsernameOrEmail()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已禁用");
        }
        // Phase 1:确保 uid 隔离 provisioning(迁移/重建后兼容,老用户登录回填 uid)
        userProvisioningService.ensureProvisioned(user.getId());
        return buildAuthResponse(user);
    }

    public UserVo currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return toVo(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtTokenProvider.generate(user.getId(), user.getUsername(), user.getRole());
        return AuthResponse.builder()
                .token(token)
                .user(toVo(user))
                .build();
    }

    private UserVo toVo(User user) {
        return UserVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
