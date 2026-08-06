package com.aiworkbench.service.opencode.process;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 每用户 opencode 进程注册表。
 * <p>
 * 维护 {@code userId → CompletableFuture<UserProcess>},用 {@code computeIfAbsent} 保证同一 userId
 * 并发首启只跑一次(后续并发调用拿到同一个 future 并 join 等待)。实际启动在独立线程池执行,
 * 避免在 ConcurrentHashMap 的 bin 锁内做长时进程拉起(ProcessBuilder + 健康轮询可达数秒)。
 * <p>
 * 由 {@link OpencodeProcessManager} 持有并调用,启动逻辑以 {@code Function<Long,UserProcess>} 注入,
 * 避免与 manager 互相依赖造成循环注入。
 */
@Slf4j
@Component
public class UserProcessRegistry {

    private final ConcurrentHashMap<Long, CompletableFuture<UserProcess>> map = new ConcurrentHashMap<>();

    /** 启动专用线程池:start() 含 ProcessBuilder + 健康轮询(数秒级),不能占用请求线程或 bin 锁。 */
    private final ExecutorService startExecutor = Executors.newCachedThreadPool(new StartThreadFactory());

    /**
     * 取得(或首次启动)某用户的 opencode 进程。阻塞至进程 READY。
     * <p>
     * 同 userId 并发调用:仅第一个真正执行 starter,其余 join 同一 future。
     * 启动失败:移除该失败 future(允许后续重试),抛 {@link BusinessException}。
     *
     * @param userId  用户 id
     * @param starter 启动函数(由 ProcessManager 提供,返回 READY 的 UserProcess)
     */
    public UserProcess getOrStart(Long userId, Function<Long, UserProcess> starter) {
        CompletableFuture<UserProcess> future = map.computeIfAbsent(userId,
                id -> CompletableFuture.supplyAsync(() -> starter.apply(id), startExecutor));
        try {
            return future.join();
        } catch (CompletionException e) {
            // 启动失败:移除失败 future,允许后续请求重试
            map.remove(userId, future);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("start opencode for user {} failed: {}", userId, cause.toString());
            if (cause instanceof BusinessException be) {
                throw be;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE.getCode(),
                    "opencode 进程启动失败: " + cause.getMessage());
        }
    }

    /** 仅返回已存在的进程;不启动。用于 touch / idleSweep / applyConfig 扇出。 */
    public UserProcess getIfPresent(Long userId) {
        CompletableFuture<UserProcess> f = map.get(userId);
        if (f == null) {
            return null;
        }
        // future 可能尚未完成(正在启动中);仅在已完成且正常时返回
        if (f.isDone() && !f.isCompletedExceptionally()) {
            return f.join();
        }
        return null;
    }

    /** 条件移除:仅当 map 中持有的 future 与给定一致时移除,避免覆盖正在重试的新 future。 */
    public void remove(Long userId, UserProcess expected) {
        CompletableFuture<UserProcess> f = map.get(userId);
        if (f != null && f.isDone() && !f.isCompletedExceptionally() && f.join() == expected) {
            map.remove(userId, f);
        }
    }

    /** 所有活跃用户 id(用于 idleSweep 与 applyConfig 扇出)。 */
    public Set<Long> activeUserIds() {
        return map.keySet();
    }

    public int activeCount() {
        return map.size();
    }

    /** 关闭线程池(@PreDestroy 由 manager 统一调度,此处保留独立关闭入口)。 */
    public void shutdown() {
        startExecutor.shutdownNow();
    }

    private static final class StartThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "opencode-start-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
