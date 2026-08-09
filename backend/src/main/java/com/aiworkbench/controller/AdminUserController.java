package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.admin.AdminUserVo;
import com.aiworkbench.dto.admin.ResetUserPasswordRequest;
import com.aiworkbench.dto.admin.UpdateUserRoleRequest;
import com.aiworkbench.dto.admin.UpdateUserStatusRequest;
import com.aiworkbench.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public R<List<AdminUserVo>> listUsers(@RequestParam(required = false) String keyword) {
        return R.ok(userService.listUsers(keyword));
    }

    @PutMapping("/{id}/status")
    @AuditLog(action = "修改用户状态", targetSpel = "#id")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest req) {
        userService.updateStatus(SecurityUtil.currentUserId(), id, req.getStatus());
        return R.ok();
    }

    @PutMapping("/{id}/role")
    @AuditLog(action = "修改用户角色", targetSpel = "#id")
    public R<Void> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateUserRoleRequest req) {
        userService.updateRole(SecurityUtil.currentUserId(), id, req.getRole());
        return R.ok();
    }

    @PutMapping("/{id}/password")
    @AuditLog(action = "重置用户密码", targetSpel = "#id")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetUserPasswordRequest req) {
        userService.resetPassword(SecurityUtil.currentUserId(), id, req.getPassword());
        return R.ok();
    }
}
