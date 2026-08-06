package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.CreateSessionRequest;
import com.aiworkbench.dto.chat.SessionVo;
import com.aiworkbench.dto.chat.UpdateSessionRequest;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatSessionMapper;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.dto.CreateSessionBody;
import com.aiworkbench.service.opencode.dto.OpencodeSessionDto;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final OpencodeClient opencodeClient;
    private final WorkspaceService workspaceService;

    public SessionVo create(CreateSessionRequest req, Long userId) {
        workspaceService.ensureUserWorkspace(userId);

        CreateSessionBody body = CreateSessionBody.builder()
                .title(req.getTitle() != null ? req.getTitle() : "New Chat")
                .agent(req.getAgent() != null ? req.getAgent() : "chat")
                .model((req.getProviderId() != null && req.getModelId() != null)
                        ? CreateSessionBody.ModelRef.builder()
                            .id(req.getModelId())
                            .providerID(req.getProviderId()).build()
                        : null)
                .build();

        OpencodeSessionDto oc = opencodeClient.createSession(userId, body);
        if (oc == null || oc.getId() == null) {
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }

        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setOpencodeSessionId(oc.getId());
        s.setTitle(oc.getTitle() != null ? oc.getTitle() : body.getTitle());
        s.setAgent(body.getAgent());
        s.setProviderId(req.getProviderId());
        s.setModelId(req.getModelId());
        s.setArchived(0);
        chatSessionMapper.insert(s);

        return toVo(s);
    }

    public List<SessionVo> list(Long userId) {
        List<ChatSession> list = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getArchived, 0)
                .orderByDesc(ChatSession::getUpdatedAt));
        return list.stream().map(this::toVo).toList();
    }

    /** 取归属会话(含越权校验) */
    public ChatSession getOwned(Long sessionId, Long userId) {
        ChatSession s = chatSessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.SESSION_NOT_FOUND);
        }
        return s;
    }

    /** 取归属会话并转 VO */
    public SessionVo get(Long sessionId, Long userId) {
        return toVo(getOwned(sessionId, userId));
    }

    public SessionVo update(Long sessionId, Long userId, UpdateSessionRequest req) {
        ChatSession s = getOwned(sessionId, userId);
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getArchived() != null) s.setArchived(req.getArchived());
        // 可清空字段:IGNORED 策略保证 null 写入(项目记忆教训)
        if (req.getProviderId() != null || req.getModelId() != null) {
            s.setProviderId(req.getProviderId());
            s.setModelId(req.getModelId());
        }
        chatSessionMapper.updateById(s);
        return toVo(s);
    }

    public void delete(Long sessionId, Long userId) {
        ChatSession s = getOwned(sessionId, userId);
        try {
            opencodeClient.deleteSession(userId, s.getOpencodeSessionId());
        } catch (Exception e) {
            log.warn("delete opencode session failed, continue to delete local: {}", e.toString());
        }
        chatSessionMapper.deleteById(sessionId);
    }

    /** 供 SSE 消费者按 opencodeSessionId 反查(可能查不到,如非本实例会话) */
    public ChatSession findByOpencodeSessionId(String opencodeSessionId) {
        return chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getOpencodeSessionId, opencodeSessionId));
    }

    /**
     * 重新绑定 opencode 会话 id(Phase 2 lazy re-bind)。
     * <p>
     * 切换到 per-user 进程后,旧 chat_session.opencode_session_id 指向的会话状态留在旧 root opencode
     * 进程里(已不存在),新进程返回 404。sendUserMessage 捕获 404 后建新会话,调本方法把新 id 写回,
     * 对用户无感(历史消息仍在 MySQL,turn-merging 按新 opencodeSessionId 经 findByOpencodeSessionId 反查)。
     */
    public void rebindOpencodeSession(Long sessionId, String newOpencodeSessionId) {
        ChatSession s = chatSessionMapper.selectById(sessionId);
        if (s == null) {
            return;
        }
        s.setOpencodeSessionId(newOpencodeSessionId);
        chatSessionMapper.updateById(s);
    }

    private SessionVo toVo(ChatSession s) {
        return SessionVo.builder()
                .id(s.getId())
                .opencodeSessionId(s.getOpencodeSessionId())
                .title(s.getTitle())
                .agent(s.getAgent())
                .providerId(s.getProviderId())
                .modelId(s.getModelId())
                .archived(s.getArchived())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
