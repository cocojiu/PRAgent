package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationDeliverySummaryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationDeliveryStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Converts notification event and delivery entities into query response DTOs.
 */
@Component
public class NotificationEventResponseAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public NotificationEventDto assembleEvent(
        NotificationEvent event,
        NotificationDeliverySummaryDto deliverySummary
    ) {
        return new NotificationEventDto(
            event.getId(),
            event.getEventKey(),
            event.getEventType(),
            event.getTaskId(),
            event.getBatchId(),
            event.getStatus(),
            event.getRetryCount(),
            format(event.getNextRetryAt()),
            event.getLastError(),
            deliverySummary,
            format(event.getCreatedAt()),
            format(event.getUpdatedAt())
        );
    }

    public NotificationDeliverySummaryDto summarizeDeliveries(List<NotificationDeliveryLog> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return new NotificationDeliverySummaryDto(List.of(), 0, 0, null);
        }
        Set<String> providers = deliveries.stream()
            .map(NotificationDeliveryLog::getProvider)
            .filter(StringUtils::hasText)
            .map(provider -> provider.trim().toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int failedCount = (int) deliveries.stream()
            .filter(delivery -> NotificationDeliveryStatus.FAILED == NotificationDeliveryStatus.from(delivery.getStatus()))
            .count();
        String latestStatus = deliveries.stream()
            .max(Comparator.comparing(
                NotificationDeliveryLog::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())
            ))
            .map(NotificationDeliveryLog::getStatus)
            .orElse(null);
        return new NotificationDeliverySummaryDto(List.copyOf(providers), deliveries.size(), failedCount, latestStatus);
    }

    public NotificationDeliveryDto assembleDelivery(NotificationDeliveryLog log) {
        return new NotificationDeliveryDto(
            log.getId(),
            log.getEventId(),
            log.getBindingId(),
            log.getTaskId(),
            log.getProvider(),
            log.getStatus(),
            log.getAttemptCount(),
            log.getFailureReason(),
            log.getRequestId(),
            format(log.getSentAt()),
            format(log.getCreatedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
