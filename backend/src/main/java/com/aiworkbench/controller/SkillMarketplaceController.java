package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.chat.MarketplaceSkillVo;
import com.aiworkbench.dto.chat.PublishSkillRequest;
import com.aiworkbench.service.SkillMarketplaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 技能广场:发布/浏览/安装技能。 */
@RestController
@RequestMapping("/api/chat/skills/marketplace")
@RequiredArgsConstructor
public class SkillMarketplaceController {

    private final SkillMarketplaceService service;

    @GetMapping
    public R<List<MarketplaceSkillVo>> list() {
        return R.ok(service.list(SecurityUtil.currentUserId()));
    }

    @GetMapping("/{id}")
    public R<MarketplaceSkillVo> detail(@PathVariable Long id) {
        return R.ok(service.detail(SecurityUtil.currentUserId(), id));
    }

    @PostMapping
    public R<MarketplaceSkillVo> publish(@Valid @RequestBody PublishSkillRequest req) {
        return R.ok(service.publish(SecurityUtil.currentUserId(), req));
    }

    @PostMapping("/{id}/install")
    public R<Void> install(@PathVariable Long id) {
        service.install(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.deleteOwn(SecurityUtil.currentUserId(), id);
        return R.ok();
    }
}
