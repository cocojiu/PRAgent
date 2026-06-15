package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationIntegrationServiceImplTest {

    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDeliveryLogMapper deliveryLogMapper = org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationChannelAdapterRegistry adapterRegistry = org.mockito.Mockito.mock(NotificationChannelAdapterRegistry.class);
    private final NotificationDispatchService dispatchService = org.mockito.Mockito.mock(NotificationDispatchService.class);
    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final NotificationIntegrationServiceImpl service = new NotificationIntegrationServiceImpl(
        bindingMapper,
        eventMapper,
        deliveryLogMapper,
        adapterRegistry,
        dispatchService,
        secretCryptoService
    );

    @Test
    void listBindingsAllowsMissingProviderFilter() {
        Page<NotificationChannelBinding> page = Page.of(1, 20);
        page.setRecords(List.of(binding()));
        page.setTotal(1);
        when(bindingMapper.selectPage(any(), any())).thenReturn(page);
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://example.com/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("secret");

        var result = service.listBindings(1, 20, null, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().provider()).isEqualTo("DINGTALK");
    }

    @Test
    void listEventsReturnsDeliverySummary() {
        Page<NotificationEvent> page = Page.of(1, 20);
        page.setRecords(List.of(event()));
        page.setTotal(1);
        when(eventMapper.selectPage(any(), any())).thenReturn(page);
        when(deliveryLogMapper.selectList(any())).thenReturn(List.of(
            delivery(1L, "DINGTALK", "FAILED", LocalDateTime.of(2026, 6, 15, 10, 0)),
            delivery(1L, "WECOM", "SUCCESS", LocalDateTime.of(2026, 6, 15, 10, 1))
        ));

        var result = service.listEvents(1, 20, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        var summary = result.items().getFirst().deliverySummary();
        assertThat(summary.providers()).containsExactly("DINGTALK", "WECOM");
        assertThat(summary.deliveryCount()).isEqualTo(2);
        assertThat(summary.failedDeliveryCount()).isEqualTo(1);
        assertThat(summary.latestDeliveryStatus()).isEqualTo("SUCCESS");
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(1L);
        binding.setName("DingTalk");
        binding.setProvider("DINGTALK");
        binding.setOrganization("octocat");
        binding.setRepository("Hello-World");
        binding.setEnabled(true);
        binding.setWebhookUrlValue("enc:webhook");
        binding.setSecretValue("enc:secret");
        binding.setNotifyReviewCompleted(true);
        binding.setNotifyReviewFailed(true);
        binding.setNotifyHumanReviewRequired(true);
        binding.setNotifyGithubComment(true);
        binding.setStatus("CONFIGURED");
        return binding;
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(1L);
        event.setEventKey("REVIEW_COMPLETED:100");
        event.setEventType("REVIEW_COMPLETED");
        event.setTaskId(100L);
        event.setStatus("DELIVERED");
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 15, 9, 59));
        event.setUpdatedAt(LocalDateTime.of(2026, 6, 15, 10, 1));
        return event;
    }

    private NotificationDeliveryLog delivery(Long eventId, String provider, String status, LocalDateTime createdAt) {
        NotificationDeliveryLog delivery = new NotificationDeliveryLog();
        delivery.setEventId(eventId);
        delivery.setProvider(provider);
        delivery.setStatus(status);
        delivery.setCreatedAt(createdAt);
        return delivery;
    }
}
