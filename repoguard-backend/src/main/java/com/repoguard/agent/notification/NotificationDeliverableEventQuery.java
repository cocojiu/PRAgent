package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliverableEventQuery {

    private final NotificationEventMapper eventMapper;

    NotificationDeliverableEventQuery(NotificationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    Optional<NotificationEvent> load(Long eventId) {
        NotificationEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            return Optional.empty();
        }
        NotificationEventStatus eventStatus = NotificationEventStatus.from(event.getStatus());
        if (NotificationEventStatus.PUBLISHED != eventStatus) {
            return Optional.empty();
        }
        return Optional.of(event);
    }
}
