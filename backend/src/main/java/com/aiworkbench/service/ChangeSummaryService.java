package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更卡片服务:每轮对话快照后,把本次变动的文件清单落库为 role=summary 消息,
 * 并通过 SSE 推送到前端对话流卡片。不再调用 LLM 生成总结文本,只保留文件变动列表。
 * <p>
 * 设计约束:
 * <ul>
 *   <li>不污染用户聊天历史 —— 结果以单独一条 role=summary 消息落库。</li>
 *   <li>静默失败 —— 失败只记 warn,绝不影响主对话流程。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeSummaryService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final WorkspaceSnapshotService snapshotService;
    private final OpencodeProperties props;
    private final ObjectMapper objectMapper;
    private final OpencodeEventRouter router;

    /** 每用户总结进行中标志,防同一用户并发重复落卡。 */
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * 对某次快照生成变更总结并落库 + 推送。由事件消费者在 session.idle 时(快照成功后)异步调用。
     *
     * @param sha 本次快照 commit 的完整 sha(若为 null 表示无变更,直接跳过)
     */
    public void summarizeAfterSnapshot(Long userId, String opencodeSessionId, String sha) {
        if (userId == null) {
            return;
        }
        // 防递归:同一用户已在总结中则跳过(scratch 会话自身 idle 也会触发本方法)
        if (inFlight.putIfAbsent(userId, Boolean.TRUE) != null) {
            log.debug("summary already in flight for user {}, skip", userId);
            return;
        }
        try {
            summarizeAfterSnapshotInner(userId, opencodeSessionId, sha);
        } catch (Exception e) {
            log.warn("change summary failed (userId={}): {}", userId, e.toString());
        } finally {
            inFlight.remove(userId);
        }
    }

    private void summarizeAfterSnapshotInner(Long userId, String opencodeSessionId, String sha) {
        // 1. 归属校验:scratch 会话/非本实例会话查不到 ChatSession → 取用户最近活跃会话兜底;仍无则跳过
        ChatSession session = opencodeSessionId != null
                ? chatSessionService.findByOpencodeSessionId(opencodeSessionId)
                : null;
        boolean resolved = session != null;
        if (session == null && userId != null) {
            session = chatSessionService.findMostRecentByUserId(userId);
        }
        if (session == null) {
            return;
        }
        // 2. 取本次变更的文件清单
        List<WorkspaceSnapshotService.ChangedFile> files = snapshotService.lastCommitChanges(userId, sha);
        if (files.isEmpty()) {
            log.debug("change summary skipped: no file changes for user {} sha {}", userId, sha);
            return;
        }
        // 3. 落库 role=summary(只含文件清单与快照 sha,不再生成 AI 总结文本)+ SSE 推送卡片
        ArrayNode filesJson = objectMapper.createArrayNode();
        for (WorkspaceSnapshotService.ChangedFile cf : files) {
            filesJson.addObject().put("status", cf.status()).put("path", cf.path());
        }
        String parentSha = snapshotService.parentSha(userId, sha);
        saveSummaryMessage(session.getId(), userId, "", sha, parentSha, filesJson);
        String payload = objectMapper.createObjectNode()
                .put("summary", "")
                .put("at", Instant.now().toString())
                .put("sha", sha)
                .put("parentSha", parentSha != null ? parentSha : "")
                .set("files", filesJson)
                .toString();
        String targetSessionId = resolved ? opencodeSessionId : session.getOpencodeSessionId();
        router.dispatch(targetSessionId, "change.summary", payload);
        log.info("change summary for user={} opencodeSessionId={} resolved={} ({} files)",
                userId, opencodeSessionId, resolved, files.size());
    }

    /** 落库一条 role=summary 的消息(展示为对话流内卡片),并把文件清单与快照 sha 存进 parts(历史可重建)。 */
    private void saveSummaryMessage(Long chatSessionId, Long userId, String summary, String sha, String parentSha, ArrayNode filesJson) {
        try {
            ObjectNode parts = objectMapper.createObjectNode();
            parts.put("summarySha", sha != null ? sha : "");
            parts.put("summaryParentSha", parentSha != null ? parentSha : "");
            parts.set("summaryFiles", filesJson);
            ChatMessage m = new ChatMessage();
            m.setSessionId(chatSessionId);
            m.setUserId(userId);
            m.setRole("summary");
            m.setContent(summary);
            m.setParts(objectMapper.writeValueAsString(parts));
            m.setStatus("completed");
            chatMessageMapper.insert(m);
        } catch (Exception e) {
            log.warn("persist summary message failed (userId={}): {}", userId, e.toString());
        }
    }
}
