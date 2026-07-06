package com.repoguard.agent.dashboard;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DashboardSnapshotExecutorConfig {

    static final String DASHBOARD_SNAPSHOT_EXECUTOR = "dashboardSnapshotExecutor";

    @Bean(name = DASHBOARD_SNAPSHOT_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService dashboardSnapshotExecutor() {
        return Executors.newSingleThreadExecutor(new DashboardSnapshotThreadFactory());
    }

    private static final class DashboardSnapshotThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "repoguard-dashboard-snapshot");
            thread.setDaemon(true);
            return thread;
        }
    }
}
