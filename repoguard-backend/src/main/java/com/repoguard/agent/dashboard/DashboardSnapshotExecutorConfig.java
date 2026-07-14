package com.repoguard.agent.dashboard;

import java.util.concurrent.ExecutorService;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DashboardSnapshotExecutorConfig {

    static final String DASHBOARD_SNAPSHOT_EXECUTOR = "dashboardSnapshotExecutor";

    @Bean(name = DASHBOARD_SNAPSHOT_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService dashboardSnapshotExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "dashboard-snapshot",
            properties.getDashboardThreads(),
            properties.getDashboardQueueCapacity()
        );
    }
}
