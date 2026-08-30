package com.aiworkbench.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 应用级线程池统一配置。
 * <p>
 * 不配置时 Spring 的 {@code @Async} 会退化为 SimpleAsyncTaskExecutor(每调用新建线程,无界);
 * 这里提供有界的共享线程池作为默认,避免高并发下线程数失控。
 */
@Configuration
public class AsyncConfig {

    /** 通用异步任务(@Async 默认执行器):有界核心/最大线程 + 有界队列 + 拒绝时抛异常由调用侧感知。 */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(8);
        e.setMaxPoolSize(16);
        e.setQueueCapacity(512);
        e.setThreadNamePrefix("aiwb-async-");
        e.setDaemon(true);
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        e.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        e.initialize();
        return e;
    }
}
