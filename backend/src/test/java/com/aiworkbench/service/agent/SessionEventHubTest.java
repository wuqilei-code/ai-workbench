package com.aiworkbench.service.agent;

import com.aiworkbench.entity.SessionEvent;
import com.aiworkbench.mapper.SessionEventMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SessionEventHub 跨副本中继的单元测试(无 Spring 上下文、无 DB,mapper 用 mock):
 * dispatch 写本地 + 出站;relay 投递"非本实例"事件、跳过自身与无订阅者的会话。
 */
class SessionEventHubTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AgentStreamBridge> noBridge() {
        return mock(ObjectProvider.class);
    }

    private SessionEvent row(long seq, String session, String origin) {
        SessionEvent e = new SessionEvent();
        e.setSeq(seq);
        e.setSessionId(session);
        e.setOrigin(origin);
        e.setType("delta");
        e.setData("{\"c\":\"x\"}");
        return e;
    }

    @Test
    void dispatch_deliversLocallyAndWritesOutbox() throws Exception {
        SessionEventMapper mapper = mock(SessionEventMapper.class);
        SessionEventHub hub = new SessionEventHub(mapper, noBridge(), true, 300, "inst-A");
        hub.initRelayWatermark();

        SseEmitter em = mock(SseEmitter.class);
        hub.subscribe("s1", em);
        hub.dispatch("s1", "delta", Map.of("c", "x"));

        verify(em).send(any(SseEmitter.SseEventBuilder.class));
        ArgumentCaptor<SessionEvent> cap = ArgumentCaptor.forClass(SessionEvent.class);
        verify(mapper).insert(cap.capture());
        assertEquals("inst-A", cap.getValue().getOrigin());
        assertEquals("s1", cap.getValue().getSessionId());
        assertEquals("delta", cap.getValue().getType());
    }

    @Test
    void relay_deliversForeignEvents_skipsOwnAndUnsubscribed() throws Exception {
        SessionEventMapper mapper = mock(SessionEventMapper.class);
        SessionEventHub hub = new SessionEventHub(mapper, noBridge(), true, 300, "inst-A");
        hub.initRelayWatermark();

        SseEmitter localForS2 = mock(SseEmitter.class);
        hub.subscribe("s2", localForS2);

        // seq1 自身(跳过)、seq2 外部且有订阅者(投递)、seq3 外部但无订阅者(丢弃)
        when(mapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(row(1, "s2", "inst-A"), row(2, "s2", "inst-B"), row(3, "s9", "inst-B")));

        hub.relay();

        verify(localForS2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        // 水位推进到最大 seq
        assertEquals(3L, readLastSeq(hub));
    }

    @Test
    void relay_disabled_doesNotWriteOutbox() throws Exception {
        SessionEventMapper mapper = mock(SessionEventMapper.class);
        SessionEventHub hub = new SessionEventHub(mapper, noBridge(), false, 300, "inst-A");

        SseEmitter em = mock(SseEmitter.class);
        hub.subscribe("s1", em);
        hub.dispatch("s1", "delta", Map.of("c", "x"));

        verify(em).send(any(SseEmitter.SseEventBuilder.class)); // 本地仍即时投递
        verify(mapper, never()).insert(any(SessionEvent.class));
    }

    private long readLastSeq(SessionEventHub hub) throws Exception {
        var f = SessionEventHub.class.getDeclaredField("lastSeq");
        f.setAccessible(true);
        return (long) f.get(hub);
    }
}
