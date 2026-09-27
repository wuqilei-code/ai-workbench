package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.notification.NotificationVo;
import com.aiworkbench.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统消息中心接口(右上角铃铛)。 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public R<List<NotificationVo>> list(@RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        return R.ok(notificationService.list(SecurityUtil.currentUserId(), limit, offset));
    }

    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationService.unreadCount(SecurityUtil.currentUserId()));
    }

    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    @PostMapping("/read-all")
    public R<Void> markAllRead() {
        notificationService.markAllRead(SecurityUtil.currentUserId());
        return R.ok();
    }
}
