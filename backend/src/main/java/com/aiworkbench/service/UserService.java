package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.admin.AdminUserVo;
import com.aiworkbench.dto.auth.AuthResponse;
import com.aiworkbench.dto.auth.LoginRequest;
import com.aiworkbench.dto.auth.RegisterRequest;
import com.aiworkbench.dto.auth.UserVo;
import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.UserMapper;
import com.aiworkbench.security.JwtTokenProvider;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.aiworkbench.service.user.UserProvisioningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserProvisioningService userProvisioningService;
    private final OpencodeProcessManager processManager;

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

    // ============ Admin 用户管理 ============

    /** 管理端用户列表:关键字(用户名/邮箱)可选,按 id 倒序。 */
    public List<AdminUserVo> listUsers(String keyword) {
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(User::getUsername, kw).or().like(User::getEmail, kw));
        }
        qw.orderByDesc(User::getId);
        return userMapper.selectList(qw).stream().map(this::toAdminVo).toList();
    }

    /** 启用/禁用用户。禁用时同步停掉其 opencode 实例;禁止对自己操作、禁止禁用最后一个 admin。 */
    public void updateStatus(Long operatorId, Long userId, Integer status) {
        if (operatorId.equals(userId)) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        User user = requireUser(userId);
        int target = (status != null && status == 1) ? 1 : 0;
        if (target == 0 && isAdminRole(user.getRole()) && countAdmins() <= 1) {
            throw new BusinessException(ResultCode.LAST_ADMIN);
        }
        User upd = new User();
        upd.setId(userId);
        upd.setStatus(target);
        userMapper.updateById(upd);
        if (target == 0) {
            try {
                processManager.stop(userId);
            } catch (Exception e) {
                log.warn("stop agent for disabled user {} failed: {}", userId, e.toString());
            }
        }
        log.info("admin {} set user {} status={}", operatorId, userId, target);
    }

    /** 修改角色(user/admin)。禁止对自己操作、禁止把最后一个 admin 降级。 */
    public void updateRole(Long operatorId, Long userId, String role) {
        if (operatorId.equals(userId)) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        User user = requireUser(userId);
        String target = "admin".equals(role) ? "admin" : "user";
        if (!"admin".equals(target) && isAdminRole(user.getRole()) && countAdmins() <= 1) {
            throw new BusinessException(ResultCode.LAST_ADMIN);
        }
        User upd = new User();
        upd.setId(userId);
        upd.setRole(target);
        userMapper.updateById(upd);
        log.info("admin {} set user {} role={}", operatorId, userId, target);
    }

    /** 重置用户密码。 */
    public void resetPassword(Long operatorId, Long userId, String newPassword) {
        User user = requireUser(userId);
        User upd = new User();
        upd.setId(userId);
        upd.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(upd);
        log.info("admin {} reset password for user {}", operatorId, userId);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    private boolean isAdminRole(String role) {
        return "admin".equals(role);
    }

    private long countAdmins() {
        return userMapper.selectCount(new QueryWrapper<User>().eq("role", "admin"));
    }

    private AdminUserVo toAdminVo(User user) {
        return AdminUserVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .uid(user.getUid())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
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
