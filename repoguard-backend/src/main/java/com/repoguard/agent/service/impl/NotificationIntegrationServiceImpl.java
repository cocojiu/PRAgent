package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.NotificationDeliverySummaryDto;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.notification.NotificationSendResult;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.NotificationIntegrationService;
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
public class NotificationIntegrationServiceImpl implements NotificationIntegrationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MASKED_SECRET = "******";
    private static final String STATUS_CONFIGURED = "CONFIGURED";
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_DELETED = "DELETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PENDING = "PENDING";

    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationEventMapper eventMapper;
    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final NotificationDispatchService dispatchService;
    private final SecretCryptoService secretCryptoService;

    public NotificationIntegrationServiceImpl(
        NotificationChannelBindingMapper bindingMapper,
        NotificationEventMapper eventMapper,
        NotificationDeliveryLogMapper deliveryLogMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        NotificationDispatchService dispatchService,
        SecretCryptoService secretCryptoService
    ) {
        this.bindingMapper = bindingMapper;
        this.eventMapper = eventMapper;
        this.deliveryLogMapper = deliveryLogMapper;
        this.adapterRegistry = adapterRegistry;
        this.dispatchService = dispatchService;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public PageResponse<NotificationBindingDto> listBindings(int page, int pageSize, String organization, String repository, String provider) {
        String normalizedProvider = StringUtils.hasText(provider) ? normalizeProvider(provider) : null;
        Page<NotificationChannelBinding> result = bindingMapper.selectPage(
            Page.of(page, pageSize),
            new QueryWrapper<NotificationChannelBinding>()
                .eq(StringUtils.hasText(organization), "organization", trim(organization))
                .eq(StringUtils.hasText(repository), "repository", trim(repository))
                .eq(normalizedProvider != null, "provider", normalizedProvider)
                .ne("status", STATUS_DELETED)
                .orderByDesc("updated_at")
        );
        return new PageResponse<>(result.getRecords().stream().map(this::toBindingDto).toList(), result.getTotal());
    }

    @Override
    @Transactional
    public NotificationBindingDto createBinding(NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        LocalDateTime now = LocalDateTime.now();
        NotificationChannelBinding binding = new NotificationChannelBinding();
        apply(binding, request, now, true);
        binding.setCreatedAt(now);
        bindingMapper.insert(binding);
        return toBindingDto(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        NotificationChannelBinding binding = requireBinding(id);
        apply(binding, request, LocalDateTime.now(), false);
        bindingMapper.updateById(binding);
        return toBindingDto(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBindingStatus(Long id, Boolean enabled) {
        NotificationChannelBinding binding = requireBinding(id);
        binding.setEnabled(Boolean.TRUE.equals(enabled));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
        return toBindingDto(binding);
    }

    @Override
    @Transactional
    public void deleteBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        binding.setEnabled(false);
        binding.setStatus(STATUS_DELETED);
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
    }

    @Override
    @Transactional
    public ConnectionTestResultDto testBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        NotificationSendResult result = adapterRegistry.get(binding.getProvider()).test(binding);
        binding.setLastCheckedAt(LocalDateTime.now());
        binding.setStatus(result.success() ? STATUS_CONNECTED : STATUS_FAILED);
        binding.setLastError(result.success() ? null : truncate(result.message(), 1024));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
        return new ConnectionTestResultDto(result.success(), result.success() ? "connected" : "failed", result.message(), format(binding.getLastCheckedAt()));
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
        event.setStatus(STATUS_PENDING);
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

    private void apply(NotificationChannelBinding binding, NotificationBindingRequest request, LocalDateTime now, boolean create) {
        binding.setName(request.name().trim());
        binding.setProvider(normalizeProvider(request.provider()));
        binding.setOrganization(request.organization().trim());
        binding.setRepository(request.repository().trim());
        binding.setEnabled(Boolean.TRUE.equals(request.enabled()));
        String existingWebhookUrl = create ? null : secretCryptoService.decrypt(binding.getWebhookUrlValue());
        String existingSecret = create ? null : secretCryptoService.decrypt(binding.getSecretValue());
        String webhookUrl = resolveSecret(existingWebhookUrl, request.webhookUrl());
        if (!StringUtils.hasText(webhookUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Webhook URL is required");
        }
        binding.setWebhookUrlValue(secretCryptoService.encrypt(webhookUrl));
        binding.setSecretValue(secretCryptoService.encrypt(resolveSecret(existingSecret, request.secret())));
        binding.setNotifyReviewCompleted(request.notifyReviewCompleted());
        binding.setNotifyReviewFailed(request.notifyReviewFailed());
        binding.setNotifyHumanReviewRequired(request.notifyHumanReviewRequired());
        binding.setNotifyGithubComment(request.notifyGithubComment());
        binding.setStatus(STATUS_CONFIGURED);
        binding.setLastError(null);
        binding.setUpdatedAt(now);
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

    private NotificationEvent requireEvent(Long id) {
        NotificationEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification event not found: " + id);
        }
        return event;
    }

    private NotificationBindingDto toBindingDto(NotificationChannelBinding binding) {
        return new NotificationBindingDto(
            binding.getId(),
            binding.getName(),
            binding.getProvider(),
            binding.getOrganization(),
            binding.getRepository(),
            binding.getEnabled(),
            hasSecret(binding.getWebhookUrlValue()) ? MASKED_SECRET : null,
            hasSecret(binding.getSecretValue()) ? MASKED_SECRET : null,
            binding.getNotifyReviewCompleted(),
            binding.getNotifyReviewFailed(),
            binding.getNotifyHumanReviewRequired(),
            binding.getNotifyGithubComment(),
            binding.getStatus(),
            format(binding.getLastCheckedAt()),
            binding.getLastError(),
            format(binding.getCreatedAt()),
            format(binding.getUpdatedAt())
        );
    }

    private NotificationEventDto toEventDto(NotificationEvent event) {
        return toEventDto(event, deliverySummary(loadDeliveries(event == null ? null : event.getId())));
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
            .filter(delivery -> STATUS_FAILED.equalsIgnoreCase(delivery.getStatus()))
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

    private String resolveSecret(String existing, String requested) {
        if (MASKED_SECRET.equals(requested)) {
            return existing;
        }
        return trim(requested);
    }

    private boolean hasSecret(String encrypted) {
        return StringUtils.hasText(secretCryptoService.decrypt(encrypted));
    }

    private String normalizeProvider(String provider) {
        return trim(provider).toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        String trimmed = trim(status);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
