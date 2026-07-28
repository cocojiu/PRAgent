package com.repoguard.agent.notification;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishExecutor implements Executor {

    private final Executor delegate;

    @Autowired
    NotificationPublishExecutor(
        @Qualifier(NotificationPublishExecutorConfig.NOTIFICATION_PUBLISH_EXECUTOR) ExecutorService delegate
    ) {
        this((Executor) delegate);
    }

    NotificationPublishExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
