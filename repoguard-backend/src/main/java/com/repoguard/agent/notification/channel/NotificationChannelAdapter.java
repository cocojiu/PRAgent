package com.repoguard.agent.notification.channel;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.notification.delivery.NotificationSendResult;

public interface NotificationChannelAdapter {

    String provider();

    NotificationSendResult send(NotificationMessage message, NotificationChannelBinding binding);

    NotificationSendResult test(NotificationChannelBinding binding);
}
