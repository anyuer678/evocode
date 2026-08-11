package com.evocode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池（AD-5：DB 状态机 + @Async + 轮询）。
 * 快扫专用池：任务短（≤30s），并发限制 ≤3（跨模块业务规则）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("quickScanExecutor")
    public Executor quickScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("quick-scan-");
        executor.initialize();
        return executor;
    }
}
