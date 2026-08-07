package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
import com.aiworkbench.service.opencode.dto.CreateSessionBody;
import com.aiworkbench.service.opencode.dto.OpencodeMessageDto;
import com.aiworkbench.service.opencode.dto.OpencodeSessionDto;
import com.aiworkbench.service.opencode.dto.PromptAsyncBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 变更总结服务:每轮对话快照后,用 scratch 会话(独立目录,避免操作真实工作区)
 * 让 opencode 依据本次变动的文件清单生成一句话"人话总结",落库为 role=summary 消息,
 * 并通过 SSE 推送到前端对话流卡片。
 * <p>
 * 设计约束:
 * <ul>
 *   <li>不污染用户聊天历史 —— 总结使用独立 scratch 会话,不在用户会话里产生 user/assistant 消息;
 *       其结果以单独一条 role=summary 消息落库。</li>
 *   <li>静默失败 —— 总结失败只记 warn,绝不影响主对话流程。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeSummaryService {

    private final OpencodeClient opencodeClient;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final WorkspaceSnapshotService snapshotService;
    private final OpencodeProperties props;
    private final ObjectMapper objectMapper;
    private final OpencodeEventRouter router;

    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long POLL_TIMEOUT_MS = 60_000L;

    /** 每用户总结进行中标志,防 scratch 会话自身 idle 再次触发总结导致的递归/风暴。 */
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
        // 3. 生成一句话总结
        String summary = generateSummary(userId, files);
        if (summary == null || summary.isBlank()) {
            log.debug("change summary empty for user {} sha {}", userId, sha);
            return;
        }
        // 4. 落库 role=summary(含文件清单与快照 sha)+ SSE 推送卡片
        ArrayNode filesJson = objectMapper.createArrayNode();
        for (WorkspaceSnapshotService.ChangedFile cf : files) {
            filesJson.addObject().put("status", cf.status()).put("path", cf.path());
        }
        String parentSha = snapshotService.parentSha(userId, sha);
        saveSummaryMessage(session.getId(), userId, summary, sha, parentSha, filesJson);
        String payload = objectMapper.createObjectNode()
                .put("summary", summary)
                .put("at", LocalDateTime.now().toString())
                .put("sha", sha)
                .put("parentSha", parentSha != null ? parentSha : "")
                .set("files", filesJson)
                .toString();
        String targetSessionId = resolved ? opencodeSessionId : session.getOpencodeSessionId();
        router.dispatch(targetSessionId, "change.summary", payload);
        log.info("change summary for user={} opencodeSessionId={} resolved={} ({} files)",
                userId, opencodeSessionId, resolved, files.size());
    }

    /**
     * 用 scratch opencode 会话生成总结。scratch 目录在用户私有数据根下,与真实工作区隔离。
     */
    private String generateSummary(Long userId, List<WorkspaceSnapshotService.ChangedFile> files) {
        int uid = props.uidOf(userId);
        Path scratch = Paths.get(props.getPerUserDataRoot(), String.valueOf(uid), "scratch", "summary-" + System.currentTimeMillis());
        try {
            Files.createDirectories(scratch);
        } catch (Exception e) {
            log.warn("create summary scratch dir failed ({}): {}", scratch, e.toString());
            return null;
        }

        StringBuilder fileList = new StringBuilder();
        for (WorkspaceSnapshotService.ChangedFile cf : files) {
            fileList.append("- [").append(cf.status()).append("] ").append(cf.path()).append('\n');
        }
        String prompt = "这是某次 AI 对话对用户工作区文件的变更清单:\n" + fileList
                + "\n请用一句简洁的中文、面向普通人的语言,概括这次 AI 做了什么,不超过 40 字,不要引用文件名之外的细节。";
        log.debug("generate summary for user {} in {} ({} files)", userId, scratch, files.size());

        OpencodeSessionDto oc;
        try {
            oc = opencodeClient.createSessionAt(userId, scratch.toString(),
                    CreateSessionBody.builder().title("AI 变更总结").build());
            if (oc == null || oc.getId() == null) {
                log.warn("create summary scratch session failed (userId={})", userId);
                return null;
            }
            opencodeClient.promptAsyncAt(userId, oc.getId(), scratch.toString(),
                    PromptAsyncBody.builder()
                            .parts(List.of(PromptAsyncBody.Part.builder().type("text").text(prompt).build()))
                            .build());
            return waitForReply(userId, oc.getId(), scratch.toString());
        } catch (Exception e) {
            log.warn("generate summary failed (userId={}): {}", userId, e.toString());
            return null;
        }
    }

    /** 轮询 scratch 会话的 assistant 文本回复(读取纯文本,不用 event 流)。 */
    private String waitForReply(Long userId, String sessionId, String directory) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                List<OpencodeMessageDto> msgs = opencodeClient.listMessagesAt(userId, sessionId, directory, 20);
                String lastAssistant = null;
                for (OpencodeMessageDto m : msgs) {
                    if (!"assistant".equals(m.getRole())) {
                        continue;
                    }
                    String text = extractText(m.getParts());
                    if (text != null && !text.isBlank()) {
                        lastAssistant = text;
                    }
                }
                if (lastAssistant != null) {
                    return lastAssistant.trim();
                }
                if (msgs != null && msgs.size() > 0 && System.currentTimeMillis() > deadline - 15_000) {
                    log.debug("summary poll still no assistant text: {} msgs roles={}",
                            msgs.size(), msgs.stream().map(OpencodeMessageDto::getRole).toList());
                }
            } catch (Exception e) {
                log.debug("poll summary reply failed: {}", e.toString());
            }
        }
        return null;
    }

    private String extractText(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            if ("text".equals(p.path("type").asText("")) && p.has("text")) {
                sb.append(p.get("text").asText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
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
