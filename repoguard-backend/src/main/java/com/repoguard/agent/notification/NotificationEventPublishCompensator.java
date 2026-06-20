package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventPublishCompensator {

    private final NotificationPublishCompensationQuery compensationQuery;
    private final NotificationDispatchService dispatchService;

    public NotificationEventPublishCompensator(
        NotificationPublishCompensationQuery compensationQuery,
        NotificationDispatchService dispatchService
    ) {
        this.compensationQuery = compensationQuery;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.publish-compensation-interval-ms:60000}")
    public void compensate() {
        List<NotificationEvent> events = compensationQuery.loadDueEvents();
        for (NotificationEvent event : events) {
            dispatchService.publishExistingEvent(event.getId());
        }
    }
}
