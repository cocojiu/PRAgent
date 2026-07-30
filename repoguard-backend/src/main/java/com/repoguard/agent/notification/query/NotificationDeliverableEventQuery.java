package com.repoguard.agent.notification.query;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationEventStatus;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliverableEventQuery {

    private final NotificationEventMapper eventMapper;

    public NotificationDeliverableEventQuery(NotificationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public Optional<NotificationEvent> load(Long eventId) {
        NotificationEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            return Optional.empty();
        }
        NotificationEventStatus eventStatus = NotificationEventStatus.from(event.getStatus());
        if (NotificationEventStatus.PUBLISHING != eventStatus
            && NotificationEventStatus.PUBLISHED != eventStatus) {
            return Optional.empty();
        }
        return Optional.of(event);
    }
}
