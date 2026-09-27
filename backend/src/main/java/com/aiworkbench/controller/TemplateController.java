package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.chat.TemplateRequest;
import com.aiworkbench.dto.chat.TemplateVo;
import com.aiworkbench.service.TemplateMarketService;
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
    private final TemplateMarketService marketService;

    // ===== 模板市场 =====

    /** 市场模板列表(带 installed/mine 标记)。 */
    @GetMapping("/market")
    public R<List<Map<String, Object>>> marketList() {
        return R.ok(marketService.list(SecurityUtil.currentUserId()));
    }

    /** 我发布的模板。 */
    @GetMapping("/market/mine")
    public R<List<Map<String, Object>>> marketMine() {
        return R.ok(marketService.listMinePublished(SecurityUtil.currentUserId()));
    }

    /** 把我创建的模板分享到市场。 */
    @PostMapping("/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable Long id) {
        return R.ok(marketService.publish(SecurityUtil.currentUserId(), id));
    }

    /** 下架我发布的模板。 */
    @DeleteMapping("/market/{id}")
    public R<Void> unpublish(@PathVariable Long id) {
        marketService.unpublish(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    /** 从市场安装到我的模板库。 */
    @PostMapping("/market/{id}/install")
    public R<Void> install(@PathVariable Long id) {
        marketService.install(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    @GetMapping
    public R<List<TemplateVo>> list() {
        return R.ok(templateService.listMine(SecurityUtil.currentUserId()));
    }

    /** 启用/停用模板(内置或自建) */
    @PostMapping("/{id}/enabled")
    public R<Void> setEnabled(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        templateService.setEnabled(SecurityUtil.currentUserId(), id, enabled);
        return R.ok();
    }

    /** 收藏/取消收藏模板;body.favorite 为空时取反。返回 { favorited: boolean } */
    @PostMapping("/{id}/favorite")
    public R<Map<String, Object>> favorite(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Boolean fav = body == null ? null : (Boolean) body.get("favorite");
        boolean result = templateService.toggleFavorite(SecurityUtil.currentUserId(), id, fav);
        return R.ok(Map.of("favorited", result));
    }

    @PostMapping
    public R<TemplateVo> create(@Valid @RequestBody TemplateRequest req) {
        return R.ok(templateService.create(SecurityUtil.currentUserId(), req));
    }

    /** 置顶/取消置顶(仅自己的模板)。body: { pinned: boolean } */
    @PostMapping("/{id}/pin")
    public R<Void> pin(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        boolean pinned = body != null && Boolean.TRUE.equals(body.get("pinned"));
        templateService.pin(SecurityUtil.currentUserId(), id, pinned);
        return R.ok();
    }

    /** 保存当前用户模板的自定义展示顺序(body: { ids: number[] })。 */
    @PostMapping("/reorder")
    public R<Void> reorder(@RequestBody Map<String, Object> body) {
        List<Long> ids = new java.util.ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try {
                    ids.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 id
                }
            }
        }
        templateService.reorder(SecurityUtil.currentUserId(), ids);
        return R.ok();
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
