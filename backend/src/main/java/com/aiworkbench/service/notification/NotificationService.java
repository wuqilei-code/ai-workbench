package com.aiworkbench.service.notification;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.notification.NotificationVo;
import com.aiworkbench.entity.Notification;
import com.aiworkbench.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统消息中心:后台事件(定时任务完成等)给用户产生的消息。
 * 右上角铃铛展示,支持未读计数、单条/全部标记已读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /** 给用户发一条消息。content/link 保证非空(避免前端空详情)。 */
    public Long notify(Long userId, String type, String title, String content, String link) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type == null || type.isBlank() ? "system" : type);
        n.setTitle(title);
        n.setContent(content == null || content.isBlank() ? title : content);
        n.setLink(link == null || link.isBlank() ? null : link);
        n.setReadFlag(0);
        notificationMapper.insert(n);
        return n.getId();
    }

    /** 用户消息列表:未读优先,再按时间新→旧(limit 条)。 */
    public List<NotificationVo> list(Long userId, int limit) {
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByAsc(Notification::getReadFlag)
                .orderByDesc(Notification::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
        return list.stream().map(this::toVo).toList();
    }

    /** 未读数。 */
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0));
    }

    /** 标记单条已读(仅本人)。 */
    public void markRead(Long userId, Long id) {
        Notification n = notificationMapper.selectById(id);
        if (n == null || !userId.equals(n.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        notificationMapper.update(new Notification(), new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1));
    }

    /** 全部已读。 */
    public void markAllRead(Long userId) {
        notificationMapper.update(new Notification(), new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1));
    }

    private NotificationVo toVo(Notification n) {
        return NotificationVo.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .link(n.getLink())
                .read(Integer.valueOf(1).equals(n.getReadFlag()))
                .createdAt(n.getCreatedAt() == null ? LocalDateTime.now() : n.getCreatedAt())
                .build();
    }
}
