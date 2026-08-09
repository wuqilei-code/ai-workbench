package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具在途看门狗:兜底「生成中但长时间无事件」的卡死会话。
 * <p>
 * 判定两类卡死:
 * <ul>
 *   <li><b>权限卡死</b>:进程 streaming == true,最近一次事件距现在超过阈值,且该会话 parts 中存在未回答的
 *       pendingPermission → opencode 死等该权限,agent loop 卡住,前端展示权限弹窗消失。</li>
 *   <li><b>工具卡死</b>:进程 streaming == true,超阈值无事件,且该会话最近的 assistant 消息存在 status=running
 *       的在途 toolCall(典型:bash 工具因后台子进程持有管道永不返回,如 Flask dev server 的 reloader 子进程),
 *       agent loop 空转,用户后续消息全部无响应。</li>
 * </ul>
 * 两类均自动调用 opencode abort 解除死锁,让 agent 结束本轮,用户重新发起即可;并会把 DB 里卡死的
 * streaming 消息补标记为 aborted,避免重载后永久转圈。带 per-user 冷却(防止误杀后立刻再杀同一用户)。
 * <p>
 * 根因修复是前端按 requestId 清除 pendingPermission + chat.md 避免触发多权限命令/避免前台跑常驻服务;
 * 本组件只是最后一道安全网,不取代弹窗修复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StuckToolWatcher {

    private final OpencodeProperties props;
    private final OpencodeProcessManager processManager;
    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final OpencodeClient opencodeClient;

    /** userId → 上次 abort 时间戳(冷却,防止同一个卡死反复被杀) */
    private final Map<Long, Long> lastAbortAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${opencode.stuck-sweep-ms:5000}")
    public void sweep() {
        for (Long userId : processManager.activeUserIds()) {
            try {
                scan(userId);
            } catch (Exception e) {
                log.debug("stuck-tool scan failed for user {}: {}", userId, e.toString());
            }
        }
    }

    private void scan(Long userId) {
        if (!processManager.isStreaming(userId)) {
            return;
        }
        Instant lastEvent = processManager.lastEventAt(userId);
        // 尚无任何事件心跳(刚启动/异常),不干预
        if (lastEvent == null || lastEvent.equals(Instant.MIN)) {
            return;
        }
        long ageMs = System.currentTimeMillis() - lastEvent.toEpochMilli();
        String sessionId = processManager.lastEventSessionId(userId);
        boolean pending = false;
        boolean runningTool = false;
        if (sessionId != null) {
            ChatSession session = chatSessionService.findByOpencodeSessionId(sessionId);
            if (session != null) {
                pending = chatMessageService.hasUnresolvedPending(session.getId());
                runningTool = chatMessageService.hasRunningTool(session.getId());
            }
        }
        // 无未回答权限、也无在途工具:可能是合理的长思考/长工具执行,不自动干预,仅记录
        if (!pending && !runningTool) {
            if (ageMs > props.getStuckToolTimeoutMs()) {
                log.warn("stuck-tool: user {} streaming with no event for {}ms (lastSession={}), no pending permission & no running tool, leaving alone",
                        userId, ageMs, sessionId);
            }
            return;
        }
        // 有在途工具/未答权限:未到提示窗口阈值则静默;进入提示窗口仅记录;到强杀阈值则 abort
        if (ageMs < props.getStuckPermissionPromptMs()) {
            return;
        }
        if (ageMs < props.getStuckToolTimeoutMs()) {
            log.warn("stuck-tool: user {} session {} {} unanswered for {}ms, will abort at {}ms",
                    userId, sessionId, pending ? "permission" : "running tool",
                    ageMs, props.getStuckToolTimeoutMs());
            return;
        }
        abort(userId, sessionId, ageMs);
    }

    private void abort(Long userId, String sessionId, long ageMs) {
        long last = lastAbortAt.getOrDefault(userId, 0L);
        if (System.currentTimeMillis() - last < props.getStuckCooldownMs()) {
            log.warn("stuck-tool: user {} session {} still stuck ({}ms) but within cooldown, skip abort",
                    userId, sessionId, ageMs);
            return;
        }
        boolean ok;
        try {
            ok = opencodeClient.abort(userId, sessionId);
        } catch (Exception e) {
            log.warn("stuck-tool: abort failed for user {} session {}: {}", userId, sessionId, e.toString());
            return;
        }
        if (ok) {
            lastAbortAt.put(userId, System.currentTimeMillis());
            log.warn("stuck-tool: aborted user {} session {} (stuck {}ms)", userId, sessionId, ageMs);
            // abort 后 opencode 会向会话输出终止消息;对卡死的 streaming 消息补标记 aborted,
            // 避免 DB/重载后永久转圈。
            try {
                ChatSession session = chatSessionService.findByOpencodeSessionId(sessionId);
                if (session != null) {
                    int rows = chatMessageService.markStreamingAborted(session.getId());
                    if (rows > 0) {
                        log.info("stuck-tool: marked {} stuck streaming message(s) aborted, sessionId={}", rows, session.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("stuck-tool: markStreamingAborted failed: {}", e.toString());
            }
        } else {
            log.warn("stuck-tool: abort returned false for user {} session {} (already idle/stopped?)", userId, sessionId);
        }
    }
}
