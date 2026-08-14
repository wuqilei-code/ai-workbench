package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.chat.TemplateRequest;
import com.aiworkbench.dto.chat.TemplateVo;
import com.aiworkbench.service.TemplateService;
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
import java.util.Map;

/** 提示词模板库(用户侧):内置只读 + 个人自建 CRUD。 */
@RestController
@RequestMapping("/api/chat/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public R<List<TemplateVo>> list() {
        return R.ok(templateService.listMine(SecurityUtil.currentUserId()));
    }

    /** 首页展示的模板(用户启用的;无启用时兜底内置) */
    @GetMapping("/home")
    public R<List<TemplateVo>> home() {
        return R.ok(templateService.homeTemplates(SecurityUtil.currentUserId()));
    }

    /** 启用/停用模板(内置或自建) */
    @PostMapping("/{id}/enabled")
    public R<Void> setEnabled(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        templateService.setEnabled(SecurityUtil.currentUserId(), id, enabled);
        return R.ok();
    }

    @PostMapping
    public R<TemplateVo> create(@Valid @RequestBody TemplateRequest req) {
        return R.ok(templateService.create(SecurityUtil.currentUserId(), req));
    }

    @PutMapping("/{id}")
    public R<TemplateVo> update(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        return R.ok(templateService.update(SecurityUtil.currentUserId(), id, req));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.delete(SecurityUtil.currentUserId(), id);
        return R.ok();
    }
}
