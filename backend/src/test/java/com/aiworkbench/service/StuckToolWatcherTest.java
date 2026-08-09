package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StuckToolWatcher 看门狗单元测试:覆盖「生成中但长时间无事件」的两类卡死判定
 * (无效权限 pending / 在途工具 running)的提示窗口、强杀阈值、冷却与 leave-alone 路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StuckToolWatcherTest {

    @Mock private OpencodeProcessManager processManager;
    @Mock private ChatMessageService chatMessageService;
    @Mock private ChatSessionService chatSessionService;
    @Mock private OpencodeClient opencodeClient;

    private OpencodeProperties props;
    private StuckToolWatcher watcher;

    @BeforeEach
    void setUp() {
        props = new OpencodeProperties();
        props.setStuckPermissionPromptMs(30_000);
        props.setStuckToolTimeoutMs(90_000);
        props.setStuckCooldownMs(120_000);
        watcher = new StuckToolWatcher(props, processManager, chatMessageService, chatSessionService, opencodeClient);
    }

    private void stubStreamingUser(long userId, Instant lastEvent, String sessionId) {
        when(processManager.activeUserIds()).thenReturn(Set.of(userId));
        when(processManager.isStreaming(userId)).thenReturn(true);
        when(processManager.lastEventAt(userId)).thenReturn(lastEvent);
        when(processManager.lastEventSessionId(userId)).thenReturn(sessionId);
        if (sessionId != null) {
            ChatSession s = new ChatSession();
            s.setId(116L);
            s.setUserId(userId);
            s.setOpencodeSessionId(sessionId);
            when(chatSessionService.findByOpencodeSessionId(sessionId)).thenReturn(s);
        }
    }

    @Test
    void notStreaming_doesNothing() {
        when(processManager.activeUserIds()).thenReturn(Set.of(1L));
        when(processManager.isStreaming(1L)).thenReturn(false);

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
        verify(chatMessageService, never()).markStreamingAborted(anyLong());
    }

    @Test
    void noHeartbeat_doesNothing() {
        stubStreamingUser(1L, null, "ses_x");

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
    }

    @Test
    void minHeartbeat_doesNothing() {
        stubStreamingUser(1L, Instant.MIN, "ses_x");

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
    }

    @Test
    void runningTool_stale_abortsAndMarksStreamingAborted() {
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);
        when(chatMessageService.hasUnresolvedPending(116L)).thenReturn(false);
        when(opencodeClient.abort(1L, "ses_x")).thenReturn(true);
        when(chatMessageService.markStreamingAborted(116L)).thenReturn(2);

        watcher.sweep();

        verify(opencodeClient).abort(1L, "ses_x");
        verify(chatMessageService).markStreamingAborted(116L);
    }

    @Test
    void pendingPermission_stale_aborts() {
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(false);
        when(chatMessageService.hasUnresolvedPending(116L)).thenReturn(true);
        when(opencodeClient.abort(1L, "ses_x")).thenReturn(true);

        watcher.sweep();

        verify(opencodeClient).abort(1L, "ses_x");
        verify(chatMessageService).markStreamingAborted(116L);
    }

    @Test
    void noPendingNoTool_leavesAloneEvenWhenStale() {
        // 修复目标:无 pendingPermission 且无 running tool 的长思考/长执行不得被误杀
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(false);
        when(chatMessageService.hasUnresolvedPending(116L)).thenReturn(false);

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
        verify(chatMessageService, never()).markStreamingAborted(anyLong());
    }

    @Test
    void withinPromptWindow_silentNoAbort() {
        stubStreamingUser(1L, Instant.now().minusSeconds(10), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
    }

    @Test
    void withinWarnWindow_noAbortYet() {
        stubStreamingUser(1L, Instant.now().minusSeconds(60), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
    }

    @Test
    void cooldown_skipsSecondAbort() {
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);
        when(chatMessageService.hasUnresolvedPending(116L)).thenReturn(false);
        when(opencodeClient.abort(1L, "ses_x")).thenReturn(true);

        watcher.sweep();
        // 第二次扫描仍卡死,但处于冷却期 → 不 abort
        watcher.sweep();

        verify(opencodeClient).abort(1L, "ses_x"); // 仅一次
        verify(chatMessageService).markStreamingAborted(116L); // 仅一次
    }

    @Test
    void abortReturnsFalse_noMarkStreamingAborted() {
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);
        when(opencodeClient.abort(1L, "ses_x")).thenReturn(false);

        watcher.sweep();

        verify(chatMessageService, never()).markStreamingAborted(anyLong());
    }

    @Test
    void abortThrows_noCrashNoMark() {
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);
        when(opencodeClient.abort(1L, "ses_x")).thenThrow(new RuntimeException("boom"));

        watcher.sweep();

        verify(chatMessageService, never()).markStreamingAborted(anyLong());
    }

    @Test
    void oneUserThrows_otherUsersStillSwept() {
        when(processManager.activeUserIds()).thenReturn(Set.of(1L, 2L));
        // user 1:会话查询抛异常 → 该用户 scan 失败被捕获
        when(processManager.isStreaming(1L)).thenReturn(true);
        when(processManager.lastEventAt(1L)).thenReturn(Instant.now().minusSeconds(200));
        when(processManager.lastEventSessionId(1L)).thenReturn("bad_ses");
        when(chatSessionService.findByOpencodeSessionId("bad_ses")).thenThrow(new RuntimeException("db down"));
        // user 2:正常工具卡死,应照常 abort
        when(processManager.isStreaming(2L)).thenReturn(true);
        when(processManager.lastEventAt(2L)).thenReturn(Instant.now().minusSeconds(200));
        when(processManager.lastEventSessionId(2L)).thenReturn("ses_y");
        ChatSession s2 = new ChatSession();
        s2.setId(216L);
        s2.setUserId(2L);
        s2.setOpencodeSessionId("ses_y");
        when(chatSessionService.findByOpencodeSessionId("ses_y")).thenReturn(s2);
        when(chatMessageService.hasRunningTool(216L)).thenReturn(true);
        when(opencodeClient.abort(2L, "ses_y")).thenReturn(true);

        watcher.sweep();

        verify(opencodeClient).abort(2L, "ses_y");
    }

    @Test
    void hasRunningToolOnly_pendingQueriesAreIndependent() {
        // 验证工具卡死判定不依赖 pendingPermission:hasUnresolvedPending 返回 false 时仍可 abort
        stubStreamingUser(1L, Instant.now().minusSeconds(200), "ses_x");
        when(chatMessageService.hasRunningTool(116L)).thenReturn(true);
        when(chatMessageService.hasUnresolvedPending(116L)).thenReturn(false);
        when(opencodeClient.abort(1L, "ses_x")).thenReturn(true);

        watcher.sweep();

        verify(opencodeClient).abort(1L, "ses_x");
        verify(chatMessageService).hasUnresolvedPending(116L);
    }

    @Test
    void sessionNotFound_noAbort() {
        when(processManager.activeUserIds()).thenReturn(Set.of(1L));
        when(processManager.isStreaming(1L)).thenReturn(true);
        when(processManager.lastEventAt(1L)).thenReturn(Instant.now().minusSeconds(200));
        when(processManager.lastEventSessionId(1L)).thenReturn("unknown_ses");
        when(chatSessionService.findByOpencodeSessionId("unknown_ses")).thenReturn(null);

        watcher.sweep();

        verify(opencodeClient, never()).abort(anyLong(), anyString());
    }
}