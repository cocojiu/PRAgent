package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationDeliverySummaryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationDeliveryStatus;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.service.NotificationEventQueryService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NotificationEventQueryServiceImpl implements NotificationEventQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationEventMapper eventMapper;
    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final NotificationDispatchService dispatchService;

    public NotificationEventQueryServiceImpl(
        NotificationEventMapper eventMapper,
        NotificationDeliveryLogMapper deliveryLogMapper,
        NotificationDispatchService dispatchService
    ) {
        this.eventMapper = eventMapper;
        this.deliveryLogMapper = deliveryLogMapper;
        this.dispatchService = dispatchService;
    }

    @Override
    public PageResponse<NotificationEventDto> listEvents(int page, int pageSize, String status, Long taskId) {
        Page<NotificationEvent> result = eventMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<NotificationEvent>()
                .eq(StringUtils.hasText(status), NotificationEvent::getStatus, normalizeStatus(status))
                .eq(taskId != null, NotificationEvent::getTaskId, taskId)
                .orderByDesc(NotificationEvent::getCreatedAt)
        );
        Map<Long, List<NotificationDeliveryLog>> deliveriesByEventId = loadDeliveriesByEventId(result.getRecords());
        return new PageResponse<>(
            result.getRecords().stream()
                .map(event -> toEventDto(event, deliverySummary(deliveriesByEventId.get(event.getId()))))
                .toList(),
            result.getTotal()
        );
    }

    @Override
    @Transactional
    public NotificationEventDto retryEvent(Long id) {
        NotificationEvent event = requireEvent(id);
        event.setStatus(NotificationEventStatus.PENDING.code());
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        event.setLastError(null);
        event.setUpdatedAt(LocalDateTime.now());
        eventMapper.updateById(event);
        dispatchService.publishExistingEvent(event.getId());
        NotificationEvent refreshed = eventMapper.selectById(id);
        return toEventDto(refreshed, deliverySummary(loadDeliveries(refreshed == null ? null : refreshed.getId())));
    }

    @Override
    public PageResponse<NotificationDeliveryDto> listDeliveries(int page, int pageSize, String status, Long taskId) {
        Page<NotificationDeliveryLog> result = deliveryLogMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<NotificationDeliveryLog>()
                .eq(StringUtils.hasText(status), NotificationDeliveryLog::getStatus, normalizeStatus(status))
                .eq(taskId != null, NotificationDeliveryLog::getTaskId, taskId)
                .orderByDesc(NotificationDeliveryLog::getCreatedAt)
        );
        return new PageResponse<>(result.getRecords().stream().map(this::toDeliveryDto).toList(), result.getTotal());
    }

    private NotificationEvent requireEvent(Long id) {
        NotificationEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification event not found: " + id);
        }
        return event;
    }

    private NotificationEventDto toEventDto(NotificationEvent event, NotificationDeliverySummaryDto deliverySummary) {
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

    private Map<Long, List<NotificationDeliveryLog>> loadDeliveriesByEventId(List<NotificationEvent> events) {
        if (events == null || events.isEmpty()) {
            return Map.of();
        }
        List<Long> eventIds = events.stream()
            .map(NotificationEvent::getId)
            .filter(id -> id != null)
            .toList();
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return deliveryLogMapper.selectList(
                new LambdaQueryWrapper<NotificationDeliveryLog>()
                    .in(NotificationDeliveryLog::getEventId, eventIds)
                    .orderByDesc(NotificationDeliveryLog::getCreatedAt)
            )
            .stream()
            .collect(Collectors.groupingBy(NotificationDeliveryLog::getEventId));
    }

    private List<NotificationDeliveryLog> loadDeliveries(Long eventId) {
        if (eventId == null) {
            return List.of();
        }
        return deliveryLogMapper.selectList(
            new LambdaQueryWrapper<NotificationDeliveryLog>()
                .eq(NotificationDeliveryLog::getEventId, eventId)
                .orderByDesc(NotificationDeliveryLog::getCreatedAt)
        );
    }

    private NotificationDeliverySummaryDto deliverySummary(List<NotificationDeliveryLog> deliveries) {
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

    private NotificationDeliveryDto toDeliveryDto(NotificationDeliveryLog log) {
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

    private String normalizeStatus(String status) {
        String trimmed = trim(status);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
