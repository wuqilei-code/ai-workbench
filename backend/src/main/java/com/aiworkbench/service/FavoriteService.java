package com.aiworkbench.service;

import com.aiworkbench.dto.my.FavoriteToggleReq;
import com.aiworkbench.dto.my.FavoriteVo;
import com.aiworkbench.entity.Favorite;
import com.aiworkbench.mapper.FavoriteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 消息收藏(迁自前端 localStorage 的后端实现)。 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final FavoriteMapper favoriteMapper;

    public List<FavoriteVo> list(Long userId) {
        List<Favorite> rows = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .orderByDesc(Favorite::getId));
        List<FavoriteVo> out = new ArrayList<>(rows.size());
        for (Favorite f : rows) {
            out.add(toVo(f));
        }
        return out;
    }

    /** 切换收藏状态:已收藏则删除(返回 false),否则新增(返回 true)。 */
    public boolean toggle(Long userId, FavoriteToggleReq req) {
        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getSessionId, req.getSessionId())
                .eq(Favorite::getMessageId, req.getMessageId())
                .last("limit 1"));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return false;
        }
        Favorite f = new Favorite();
        f.setUserId(userId);
        f.setSessionId(req.getSessionId());
        f.setMessageId(req.getMessageId());
        f.setRole(req.getRole() == null ? "assistant" : req.getRole());
        f.setContent(clip(req.getContent(), 1000));
        f.setTurnId(req.getTurnId());
        f.setSessionTitle(req.getSessionTitle());
        favoriteMapper.insert(f);
        return true;
    }

    public void remove(Long userId, Long sessionId, Long messageId) {
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getSessionId, sessionId)
                .eq(Favorite::getMessageId, messageId));
    }

    private FavoriteVo toVo(Favorite f) {
        return FavoriteVo.builder()
                .id(f.getId())
                .sessionId(f.getSessionId())
                .messageId(f.getMessageId())
                .role(f.getRole())
                .content(f.getContent())
                .turnId(f.getTurnId())
                .sessionTitle(f.getSessionTitle())
                .createdAt(f.getCreatedAt() == null ? 0L
                        : f.getCreatedAt().atZone(ZONE).toInstant().toEpochMilli())
                .build();
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
