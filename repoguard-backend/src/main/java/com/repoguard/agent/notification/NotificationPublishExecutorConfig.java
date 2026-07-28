package com.repoguard.agent.notification;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotificationPublishExecutorConfig {

    static final String NOTIFICATION_PUBLISH_EXECUTOR = "notificationPublishWorkerExecutor";

    @Bean(name = NOTIFICATION_PUBLISH_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService notificationPublishExecutor(
        BoundedExecutorFactory factory,
        AsyncExecutorProperties properties
    ) {
        return factory.create(
            "notification-publish",
            properties.getNotificationPublishThreads(),
            properties.getNotificationPublishQueueCapacity()
        );
    }
}
