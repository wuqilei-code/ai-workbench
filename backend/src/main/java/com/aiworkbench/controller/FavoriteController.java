package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.my.FavoriteToggleReq;
import com.aiworkbench.dto.my.FavoriteVo;
import com.aiworkbench.service.FavoriteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 消息收藏(个人)。 */
@RestController
@RequestMapping("/api/my/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @GetMapping
    public R<List<FavoriteVo>> list(@RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Integer offset) {
        return R.ok(favoriteService.list(SecurityUtil.currentUserId(), limit, offset));
    }

    /** 收藏/取消收藏:返回 true=已收藏,false=已取消。 */
    @PostMapping
    public R<Boolean> toggle(@Valid @RequestBody FavoriteToggleReq req) {
        return R.ok(favoriteService.toggle(SecurityUtil.currentUserId(), req));
    }

    @DeleteMapping
    public R<Void> remove(@RequestParam Long sessionId, @RequestParam Long messageId) {
        favoriteService.remove(SecurityUtil.currentUserId(), sessionId, messageId);
        return R.ok();
    }
}
