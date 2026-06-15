package com.repoguard.agent.notification;

public interface NotificationEventPublisher {

    void publish(NotificationEventMessage message);
}
