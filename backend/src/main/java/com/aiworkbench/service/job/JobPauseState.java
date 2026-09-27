package com.aiworkbench.service.job;

import com.aiworkbench.config.AgentJobProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务全局暂停开关:暂停后不再触发/派发新任务(已在运行的照常完成)。
 * 初始值来自配置 {@code agent-jobs.paused};可在运行时经管理员接口切换(内存态,重启复位)。
 */
@Component
@RequiredArgsConstructor
public class JobPauseState {

    private final AgentJobProperties props;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        paused.set(props.isPaused());
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }
}
