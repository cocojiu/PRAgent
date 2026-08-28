package com.repoguard.agent.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.notification.NotificationEventMessage;
import com.repoguard.agent.mapper.TenantCatalogMapper;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRuntimeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationDeliveryTenantContextTest {

    private final NotificationDeliveryClaimService claimService = mock(NotificationDeliveryClaimService.class);
    private final NotificationEventPayloadParser payloadParser = mock(NotificationEventPayloadParser.class);
    private final NotificationBindingBatchDeliveryService deliveryService =
        mock(NotificationBindingBatchDeliveryService.class);
    private final NotificationDeliveryCompletionService completionService =
        mock(NotificationDeliveryCompletionService.class);
    private final NotificationDeliveryWorkerMetricsRecorder metrics =
        mock(NotificationDeliveryWorkerMetricsRecorder.class);
    private final Channel channel = mock(Channel.class);

    @AfterEach
    void tenantContextIsAlwaysCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void messageTenantScopesDeliveryAndIsClearedBeforeAcknowledgement() throws Exception {
        when(metrics.startedAt()).thenReturn(1L);
        when(claimService.claim(11L)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).isEqualTo(23L);
            return Optional.empty();
        });
        NotificationDeliveryWorker worker = worker(null);

        worker.handle(message(23L), channel, 101L);

        verify(channel).basicAck(101L, false);
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void legacyMessageResolvesTenantFromDatabaseBeforeDelivery() throws Exception {
        when(metrics.startedAt()).thenReturn(2L);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(11L))).thenReturn(31L);
        when(claimService.claim(11L)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).isEqualTo(31L);
            return Optional.empty();
        });

        worker(jdbcTemplate).handle(message(null), channel, 102L);

        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), eq(11L));
        verify(channel).basicAck(102L, false);
    }

    @Test
    void invalidMessageTenantIsRejectedWithoutEnteringDelivery() throws Exception {
        when(metrics.startedAt()).thenReturn(3L);

        worker(null).handle(message(0L), channel, 103L);

        verify(channel).basicReject(103L, false);
        verify(claimService, never()).claim(11L);
    }

    @Test
    void suspendedTenantMessageIsRejectedBeforeDelivery() throws Exception {
        when(metrics.startedAt()).thenReturn(4L);
        TenantCatalogMapper tenantCatalogMapper = mock(TenantCatalogMapper.class);
        TenantRuntimeGuard tenantRuntimeGuard = new TenantRuntimeGuard(tenantCatalogMapper);

        worker(mock(JdbcTemplate.class), tenantRuntimeGuard).handle(message(23L), channel, 104L);

        verify(channel).basicReject(104L, false);
        verify(metrics).recordConsumed(4L, "rejected", "notification_tenant_inactive");
        verify(claimService, never()).claim(11L);
    }

    @Test
    void messageTraceIdIsScopedAcrossDeliveryAndClearedAfterAcknowledgement() throws Exception {
        when(metrics.startedAt()).thenReturn(5L);
        when(claimService.claim(11L)).thenAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-delivery-11");
            return Optional.empty();
        });

        worker(null).handle(message(23L, "trace-delivery-11"), channel, 105L);

        verify(channel).basicAck(105L, false);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void compatibilityConstructorCapturesPublisherTenant() {
        NotificationEventMessage message;
        try (TenantContext.Scope _ = TenantContext.withTenant(44L)) {
            message = new NotificationEventMessage(11L, "event", "TYPE", 12L, 13L);
        }

        assertThat(message.tenantId()).isEqualTo(44L);
    }

    private NotificationDeliveryWorker worker(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            return new NotificationDeliveryWorker(
                claimService,
                payloadParser,
                deliveryService,
                completionService,
                metrics,
                new NotificationDeliveryFailureClassifier(),
                new NotificationDeliveryLogContextFormatter()
            );
        }
        return new NotificationDeliveryWorker(
            claimService,
            payloadParser,
            deliveryService,
            completionService,
            metrics,
            new NotificationDeliveryFailureClassifier(),
            new NotificationDeliveryLogContextFormatter(),
            jdbcTemplate
        );
    }

    private NotificationDeliveryWorker worker(
        JdbcTemplate jdbcTemplate,
        TenantRuntimeGuard tenantRuntimeGuard
    ) {
        return new NotificationDeliveryWorker(
            claimService,
            payloadParser,
            deliveryService,
            completionService,
            metrics,
            new NotificationDeliveryFailureClassifier(),
            new NotificationDeliveryLogContextFormatter(),
            jdbcTemplate,
            tenantRuntimeGuard
        );
    }

    private NotificationEventMessage message(Long tenantId) {
        return message(tenantId, null);
    }

    private NotificationEventMessage message(Long tenantId, String traceId) {
        return new NotificationEventMessage(11L, "event-11", "TYPE", 12L, 13L, tenantId, traceId);
    }
}
