package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.skill.UserSkillRequest;
import com.aiworkbench.dto.skill.UserSkillVo;
import com.aiworkbench.service.UserSkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户技能(自建/安装;私有/公开;安装款只读)。 */
@RestController
@RequestMapping("/api/chat/skills/mine")
@RequiredArgsConstructor
public class UserSkillController {

    private final UserSkillService userSkillService;

    @GetMapping
    public R<List<UserSkillVo>> list() {
        return R.ok(userSkillService.list(SecurityUtil.currentUserId()));
    }

    @PostMapping
    @AuditLog(action = "创建技能")
    public R<UserSkillVo> create(@Valid @RequestBody UserSkillRequest req) {
        return R.ok(userSkillService.create(SecurityUtil.currentUserId(), req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "编辑技能", targetSpel = "#id")
    public R<UserSkillVo> update(@PathVariable Long id, @RequestBody UserSkillRequest req) {
        return R.ok(userSkillService.update(SecurityUtil.currentUserId(), id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "删除技能", targetSpel = "#id")
    public R<Void> delete(@PathVariable Long id) {
        userSkillService.delete(SecurityUtil.currentUserId(), id);
        return R.ok();
    }
}