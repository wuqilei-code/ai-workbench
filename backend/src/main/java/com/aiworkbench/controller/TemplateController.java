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
