package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.CreateSessionRequest;
import com.aiworkbench.dto.chat.SessionVo;
import com.aiworkbench.dto.chat.UpdateSessionRequest;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final WorkspaceService workspaceService;
    private final com.aiworkbench.mapper.ChatMessageMapper chatMessageMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * agentSessionId → ChatSession 进程内缓存(访问序 LRU,容量 512)。
     * <p>
     * SSE 流式期间每条事件都会经 {@link #findByAgentSessionId} 反查会话,
     * 直接查库是典型的 N+1(数百次/分钟)。会话 id 全局唯一且不随消息流变化,
     * 缓存到进程生命周期级别安全。delete 时主动失效,无脏读。
     */
    private final Map<String, ChatSession> sessionCache = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ChatSession> eldest) {
            return size() > 512;
        }
    };

    public SessionVo create(CreateSessionRequest req, Long userId) {
        // deepagents 单轨:不创建远端会话,合成 "da-" id 落库;
        // SSE 路由与 AgentStreamBridge 均以此 id 为 key(前端经 events 接口以该 id 订阅)。
        // 工作区由 agent-service 按 user_id 自管;此处仅确保 Linux 侧目录存在(文件 API 可用)。
        workspaceService.ensureUserWorkspace(userId);

        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setAgentSessionId("da-" + java.util.UUID.randomUUID());
        s.setTitle(req.getTitle() != null ? req.getTitle() : "New Chat");
        s.setAgent(req.getAgent() != null ? req.getAgent() : "chat");
        s.setProviderId(isModelRef(req.getProviderId(), req.getModelId()) ? req.getProviderId() : null);
        s.setModelId(isModelRef(req.getProviderId(), req.getModelId()) ? req.getModelId() : null);
        s.setSkills(encodeSkills(req.getSkills()));
        s.setArchived(0);
        s.setPinned(0);
        chatSessionMapper.insert(s);

        // created_at/updated_at 由 DB DEFAULT 维护,insert 后实体里仍是 null;
        // 回查一次拿到真实时间戳,否则前端新会话的 updatedAt 为空、列表排序会把它归到「更早」沉底。
        return toVo(chatSessionMapper.selectById(s.getId()));
    }

    /** provider/model 是否构成有效模型引用(空串视为未指定)。 */
    private boolean isModelRef(String providerId, String modelId) {
        return providerId != null && !providerId.isBlank()
                && modelId != null && !modelId.isBlank();
    }

    /** 导入会话:解析导出的 JSON,创建新会话并批量恢复历史消息。 */
    public SessionVo importSession(Long userId, String jsonText) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonText);
            String title = root.path("sessionTitle").asText("导入的会话");
            com.aiworkbench.dto.chat.CreateSessionRequest req = new com.aiworkbench.dto.chat.CreateSessionRequest();
            req.setTitle(title);
            req.setAgent("chat");
            SessionVo vo = create(req, userId);

            com.fasterxml.jackson.databind.JsonNode msgs = root.path("messages");
            if (msgs.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode m : msgs) {
                    com.aiworkbench.entity.ChatMessage cm = new com.aiworkbench.entity.ChatMessage();
                    cm.setSessionId(vo.getId());
                    cm.setUserId(userId);
                    cm.setRole(m.path("role").asText("assistant"));
                    cm.setContent(m.path("content").asText(""));
                    cm.setStatus(m.path("status").asText("completed"));
                    cm.setCompactionSummary(m.path("compactionSummary").asInt(0));
                    if (m.hasNonNull("parts")) {
                        cm.setParts(m.get("parts").toString());
                    }
                    if (m.hasNonNull("createdAt")) {
                        try {
                            cm.setCreatedAt(java.time.LocalDateTime.parse(m.path("createdAt").asText()));
                        } catch (Exception ignore) {}
                    }
                    chatMessageMapper.insert(cm);
                }
            }
            return vo;
        } catch (Exception e) {
            log.warn("import session failed: {}", e.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "导入会话失败: " + e.getMessage());
        }
    }

    public List<SessionVo> list(Long userId) {
        List<ChatSession> list = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getArchived, 0)
                .orderByDesc(ChatSession::getPinned)
                .orderByDesc(ChatSession::getUpdatedAt));
        return list.stream().map(this::toVo).toList();
    }

    /**
     * 会话搜索:标题或消息内容(含 parts)模糊匹配。归档与非归档会话一起返回,
     * 结果按更新时间倒序(前端展示命中顺序)。limit 50 防慢查询。
     */
    public List<SessionVo> search(String q, Long userId) {
        String like = "%" + q.trim() + "%";
        List<ChatSession> list = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .and(w -> w.like(ChatSession::getTitle, like)
                        .or()
                        .apply("exists (select 1 from chat_message m where m.session_id = chat_session.id and (m.content like {0} or m.parts like {1}))",
                                like, like))
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("limit 50"));
        return list.stream().map(this::toVo).toList();
    }

    /** 已归档会话列表(按更新时间倒序) */
    public List<SessionVo> listArchived(Long userId) {
        List<ChatSession> list = chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getArchived, 1)
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

    /** 按 DB id 取会话(不限用户,内部服务用;外部调用须自行校验归属)。 */
    public ChatSession findById(Long sessionId) {
        return sessionId == null ? null : chatSessionMapper.selectById(sessionId);
    }

    /** 用户最近活跃(未归档)的会话。无则 null。 */
    public ChatSession findMostRecentByUserId(Long userId) {
        return chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getArchived, 0)
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("limit 1"));
    }

    /** 取归属会话并转 VO */
    public SessionVo get(Long sessionId, Long userId) {
        return toVo(getOwned(sessionId, userId));
    }

    public SessionVo update(Long sessionId, Long userId, UpdateSessionRequest req) {
        ChatSession s = getOwned(sessionId, userId);
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getArchived() != null) s.setArchived(req.getArchived());
        if (req.getPinned() != null) s.setPinned(req.getPinned());
        if (req.getSkills() != null) s.setSkills(encodeSkills(req.getSkills()));
        // 可清空字段:IGNORED 策略保证 null 写入(项目记忆教训)
        if (req.getProviderId() != null || req.getModelId() != null) {
            s.setProviderId(req.getProviderId());
            s.setModelId(req.getModelId());
        }
        // 切换 agent:由 agent-service 服务端配置决定,本地仅持久化(下一条消息透传 s.agent)
        if (req.getAgent() != null) {
            s.setAgent(req.getAgent());
        }
        chatSessionMapper.updateById(s);
        return toVo(s);
    }

    public void delete(Long sessionId, Long userId) {
        ChatSession s = getOwned(sessionId, userId);
        chatSessionMapper.deleteById(sessionId);
        // 缓存失效:防止删除后残留会话仍被事件流反查命中
        synchronized (sessionCache) {
            sessionCache.remove(s.getAgentSessionId());
        }
    }

    /** 按 agentSessionId 反查(SSE 消费者/桥用;可能查不到,如非本部署会话)。走进程内缓存避免流式 N+1。 */
    public ChatSession findByAgentSessionId(String agentSessionId) {
        if (agentSessionId == null) {
            return null;
        }
        synchronized (sessionCache) {
            ChatSession cached = sessionCache.get(agentSessionId);
            if (cached != null) {
                return cached;
            }
        }
        ChatSession s = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getAgentSessionId, agentSessionId));
        if (s != null) {
            synchronized (sessionCache) {
                sessionCache.put(agentSessionId, s);
            }
        }
        return s;
    }

    private SessionVo toVo(ChatSession s) {
        return SessionVo.builder()
                .id(s.getId())
                .agentSessionId(s.getAgentSessionId())
                .title(s.getTitle())
                .agent(s.getAgent())
                .skills(decodeSkills(s.getSkills()))
                .providerId(s.getProviderId())
                .modelId(s.getModelId())
                .archived(s.getArchived())
                .pinned(s.getPinned())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private String encodeSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(skills);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> decodeSkills(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
