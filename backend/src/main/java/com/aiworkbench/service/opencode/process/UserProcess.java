package com.aiworkbench.service.opencode.process;

import com.aiworkbench.service.opencode.OpencodeInstanceEventConsumer;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单用户 opencode 进程句柄:持有端口、子进程、实例 WebClient、事件消费者、活动时间戳。
 * 由 {@link OpencodeProcessManager} 创建,登记到 {@link UserProcessRegistry}。
 */
public class UserProcess {

    public enum State { STARTING, READY, STOPPING, DEAD }

    private final long userId;
    private final int uid;
    private final int gid;
    private final int port;
    private final Process process;
    private final WebClient webClient;
    private final OpencodeInstanceEventConsumer eventConsumer;
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private volatile Instant lastActivityAt = Instant.now();
    /** 是否正在生成(由 SSE 消费者上报;applyConfig 据此跳过重启,避免打断活跃对话) */
    private volatile boolean streaming = false;
    /** 最近一次事件到达时间(opencode 全局事件流;看门狗用它判定“在途但长时间无事件”的卡死) */
    private volatile Instant lastEventAt = Instant.MIN;
    /** 最近事件所属 opencode 会话 id(看门狗 abort 定位用) */
    private volatile String lastEventSessionId;
    /** 最近事件类型(仅排查日志用) */
    private volatile String lastEventType;

    public UserProcess(long userId, int uid, int gid, int port, Process process,
                       WebClient webClient, OpencodeInstanceEventConsumer eventConsumer) {
        this.userId = userId;
        this.uid = uid;
        this.gid = gid;
        this.port = port;
        this.process = process;
        this.webClient = webClient;
        this.eventConsumer = eventConsumer;
        this.state.set(State.READY);
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    public boolean idleBefore(Instant deadline) {
        return lastActivityAt.isBefore(deadline);
    }

    public long getUserId() { return userId; }
    public int getUid() { return uid; }
    public int getGid() { return gid; }
    public int getPort() { return port; }
    public Process getProcess() { return process; }
    public WebClient getWebClient() { return webClient; }
    public OpencodeInstanceEventConsumer getEventConsumer() { return eventConsumer; }
    public State getState() { return state.get(); }
    public void setState(State s) { state.set(s); }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }

    /** 记录一次事件到达(推进 lastEventAt 与排查信息)。看门狗据此判定“流式在途但无事件”超时。 */
    public void noteEvent(String sessionId, String type) {
        this.lastEventAt = Instant.now();
        this.lastEventSessionId = sessionId;
        this.lastEventType = type;
    }

    public Instant getLastEventAt() { return lastEventAt; }
    public String getLastEventSessionId() { return lastEventSessionId; }
    public String getLastEventType() { return lastEventType; }
}
