package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DashboardSnapshotExecutorConfigTest {

    @Test
    void registersNamedDashboardSnapshotExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                DashboardSnapshotExecutorConfig.class,
                DashboardSnapshotStore.class,
                AsyncExecutorProperties.class,
                BoundedExecutorFactory.class,
                SimpleMeterRegistry.class
            );
            context.refresh();

            ExecutorService executor = context.getBean(
                DashboardSnapshotExecutorConfig.DASHBOARD_SNAPSHOT_EXECUTOR,
                ExecutorService.class
            );
            DashboardSnapshotStore store = context.getBean(DashboardSnapshotStore.class);

            assertThat(executor).isNotNull();
            assertThat(store).isNotNull();
        }
    }
}
