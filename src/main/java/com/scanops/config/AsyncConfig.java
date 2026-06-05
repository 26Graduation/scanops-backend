package com.scanops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * ZAP 스캔 파이프라인 시작/종료 처리용 스레드 풀.
     * - accessUrl, startSpider, startActiveScan, getAlerts, saveVulnerabilities 등 실제 작업 담당
     * - polling 중에는 이 스레드를 점유하지 않음
     */
    @Bean("scanExecutor")
    public Executor scanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("scan-async-");
        executor.initialize();
        return executor;
    }

    /**
     * ZAP 진행률 폴링 전용 스케줄러.
     * - scan-async 스레드와 완전히 분리되어 5초마다 ZAP API 체크
     * - 이 스레드는 HTTP 폴링만 수행하므로 DB 커넥션을 점유하지 않음
     */
    @Bean("zapPollScheduler")
    public ScheduledExecutorService zapPollScheduler() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "zap-poll-" + count.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        // 동시 스캔 최대 4건 폴링 가능 (scanExecutor maxPoolSize 절반)
        return Executors.newScheduledThreadPool(4, factory);
    }
}
