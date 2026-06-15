package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;

public interface NotificationChannelAdapter {

    String provider();

    NotificationSendResult send(NotificationMessage message, NotificationChannelBinding binding);

    NotificationSendResult test(NotificationChannelBinding binding);
}
