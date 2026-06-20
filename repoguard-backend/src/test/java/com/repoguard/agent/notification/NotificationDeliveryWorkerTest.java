package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDeliveryWorkerTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationDeliveryLogMapper deliveryLogMapper = org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationChannelAdapter adapter = org.mockito.Mockito.mock(NotificationChannelAdapter.class);

    @Test
    void deliversSameEventToMultipleRepositoryBindingsIndependently() throws Exception {
        when(adapter.provider()).thenReturn("DINGTALK");
        NotificationChannelAdapterRegistry registry = new NotificationChannelAdapterRegistry(List.of(adapter));
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
            new NotificationDeliverableEventQuery(eventMapper),
            new NotificationEventPayloadParser(new ObjectMapper()),
            bindingBatchDeliveryService(registry),
            deliveryCompletionService(),
            new NotificationDeliveryEventStateUpdater(eventMapper)
        );
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.success("request-1", "ok"));
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(bindingMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding(1L), binding(2L)));
        when(deliveryLogMapper.selectCount(any())).thenReturn(0L);

        worker.deliver(11L);

        ArgumentCaptor<NotificationDeliveryLog> logCaptor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        org.mockito.Mockito.verify(deliveryLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).extracting(NotificationDeliveryLog::getBindingId).containsExactly(1L, 2L);
        assertThat(logCaptor.getAllValues()).extracting(NotificationDeliveryLog::getStatus).containsOnly("SUCCESS");
    }

    @Test
    void failedDeliveryMarksEventDeliveryFailedAndStoresFailedLog() throws Exception {
        when(adapter.provider()).thenReturn("DINGTALK");
        NotificationChannelAdapterRegistry registry = new NotificationChannelAdapterRegistry(List.of(adapter));
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
            new NotificationDeliverableEventQuery(eventMapper),
            new NotificationEventPayloadParser(new ObjectMapper()),
            bindingBatchDeliveryService(registry),
            deliveryCompletionService(),
            new NotificationDeliveryEventStateUpdater(eventMapper)
        );
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.failed("request-1", "timeout"));
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(bindingMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding(1L)));
        when(deliveryLogMapper.selectCount(any())).thenReturn(0L);

        worker.deliver(11L);

        ArgumentCaptor<NotificationDeliveryLog> logCaptor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        org.mockito.Mockito.verify(deliveryLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(logCaptor.getValue().getFailureReason()).isEqualTo("timeout");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        org.mockito.Mockito.verify(eventMapper, org.mockito.Mockito.times(2)).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getAllValues().getLast().getParamNameValuePairs())
            .containsValue("DELIVERY_FAILED");
    }

    @Test
    void skipsBindingWhenSuccessfulDeliveryAlreadyExists() throws Exception {
        when(adapter.provider()).thenReturn("DINGTALK");
        NotificationChannelAdapterRegistry registry = new NotificationChannelAdapterRegistry(List.of(adapter));
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
            new NotificationDeliverableEventQuery(eventMapper),
            new NotificationEventPayloadParser(new ObjectMapper()),
            bindingBatchDeliveryService(registry),
            deliveryCompletionService(),
            new NotificationDeliveryEventStateUpdater(eventMapper)
        );
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(bindingMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding(1L)));
        when(deliveryLogMapper.selectCount(any())).thenReturn(1L);

        worker.deliver(11L);

        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).send(any(), any());
        org.mockito.Mockito.verify(deliveryLogMapper, org.mockito.Mockito.never())
            .insert(any(NotificationDeliveryLog.class));
    }

    private NotificationEvent event() throws Exception {
        NotificationMessage message = new NotificationMessage(
            NotificationEventType.REVIEW_COMPLETED.code(),
            42L,
            null,
            "octocat",
            "Hello-World",
            7,
            "Improve review flow",
            "COMPLETED",
            "LOW",
            1,
            0,
            0,
            0,
            "/repoguard/tasks/42"
        );
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setTaskId(42L);
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setStatus("PUBLISHED");
        event.setRetryCount(0);
        event.setPayload(new ObjectMapper().writeValueAsString(message));
        return event;
    }

    private NotificationChannelBinding binding(Long id) {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(id);
        binding.setProvider("DINGTALK");
        binding.setEnabled(true);
        binding.setNotifyReviewCompleted(true);
        binding.setNotifyReviewFailed(true);
        binding.setNotifyHumanReviewRequired(true);
        binding.setNotifyGithubComment(true);
        return binding;
    }

    private NotificationBindingBatchDeliveryService bindingBatchDeliveryService(
        NotificationChannelAdapterRegistry registry
    ) {
        return new NotificationBindingBatchDeliveryService(
            new NotificationCandidateBindingQuery(bindingMapper),
            new NotificationBindingDeliveryService(
                deliveryLogMapper,
                registry,
                new NotificationDeliveryLogFactory(),
                new NotificationBindingMatcher(),
                new NotificationSuccessfulDeliveryQuery(deliveryLogMapper)
            )
        );
    }

    private NotificationDeliveryCompletionService deliveryCompletionService() {
        NotificationDeliveryEventStateUpdater eventStateUpdater =
            new NotificationDeliveryEventStateUpdater(eventMapper);
        return new NotificationDeliveryCompletionService(
            new NotificationDeliveryFailurePolicy(),
            eventStateUpdater
        );
    }
}
