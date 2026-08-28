package com.repoguard.agent.tenancy;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduledJobLeaseExecutorConfig {

    public static final String HEARTBEAT_EXECUTOR = "scheduledJobLeaseHeartbeatExecutor";

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier(HEARTBEAT_EXECUTOR)
    ScheduledExecutorService scheduledJobLeaseHeartbeatExecutor(ScheduledJobLeaseProperties properties) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            properties.getHeartbeatThreads(),
            new HeartbeatThreadFactory()
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static final class HeartbeatThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                runnable,
                "repoguard-scheduled-lease-heartbeat-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
