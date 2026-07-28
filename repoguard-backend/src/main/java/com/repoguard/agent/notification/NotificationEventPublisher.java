package com.repoguard.agent.notification;

public interface NotificationEventPublisher {

    void publish(NotificationEventMessage message);

    default void publishOnce(NotificationEventMessage message) {
        publish(message);
    }
}
