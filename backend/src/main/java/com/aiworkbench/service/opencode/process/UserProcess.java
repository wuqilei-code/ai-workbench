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
}
