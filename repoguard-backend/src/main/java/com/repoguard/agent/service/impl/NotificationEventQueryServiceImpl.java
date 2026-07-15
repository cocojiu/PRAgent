package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.service.NotificationEventQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NotificationEventQueryServiceImpl implements NotificationEventQueryService {

    private static final List<String> MANUAL_RETRYABLE_STATUSES = List.of(
        NotificationEventStatus.PUBLISH_FAILED.code(),
        NotificationEventStatus.DELIVERY_FAILED.code(),
        NotificationEventStatus.DEAD.code()
    );

    private final NotificationEventMapper eventMapper;
    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final NotificationDispatchService dispatchService;
    private final NotificationEventResponseAssembler responseAssembler;

    public NotificationEventQueryServiceImpl(
        NotificationEventMapper eventMapper,
        NotificationDeliveryLogMapper deliveryLogMapper,
        NotificationDispatchService dispatchService,
        NotificationEventResponseAssembler responseAssembler
    ) {
        this.eventMapper = eventMapper;
        this.deliveryLogMapper = deliveryLogMapper;
        this.dispatchService = dispatchService;
        this.responseAssembler = responseAssembler;
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
                .map(event -> responseAssembler.assembleEvent(
                    event,
                    responseAssembler.summarizeDeliveries(deliveriesByEventId.get(event.getId()))
                ))
                .toList(),
            result.getTotal()
        );
    }

    @Override
    @Transactional
    public NotificationEventDto retryEvent(Long id) {
        NotificationEvent event = requireEvent(id);
        ensureManualRetryAllowed(event);
        LocalDateTime now = LocalDateTime.now();
        int updated = eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", id)
                .in("status", MANUAL_RETRYABLE_STATUSES)
                .isNull("publish_claimed_at")
                .set("status", NotificationEventStatus.PENDING.code())
                .set("retry_count", 0)
                .set("next_retry_at", now)
                .set("last_error", null)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", now)
        );
        if (updated != 1) {
            throw retryRejected(id);
        }
        dispatchService.publishExistingEvent(event.getId());
        NotificationEvent refreshed = eventMapper.selectById(id);
        return responseAssembler.assembleEvent(
            refreshed,
            responseAssembler.summarizeDeliveries(loadDeliveries(refreshed == null ? null : refreshed.getId()))
        );
    }

    private void ensureManualRetryAllowed(NotificationEvent event) {
        if (!MANUAL_RETRYABLE_STATUSES.contains(event.getStatus()) || event.getPublishClaimedAt() != null) {
            throw retryRejected(event.getId());
        }
    }

    private BusinessException retryRejected(Long id) {
        return new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Notification event is not retryable or is already being processed: " + id
        );
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
        return new PageResponse<>(
            result.getRecords().stream().map(responseAssembler::assembleDelivery).toList(),
            result.getTotal()
        );
    }

    private NotificationEvent requireEvent(Long id) {
        NotificationEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification event not found: " + id);
        }
        return event;
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

    private String normalizeStatus(String status) {
        String trimmed = trim(status);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

}
