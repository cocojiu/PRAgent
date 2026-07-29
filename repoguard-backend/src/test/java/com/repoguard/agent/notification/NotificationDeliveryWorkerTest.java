package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import com.repoguard.agent.notification.binding.NotificationBindingMatcher;
import com.repoguard.agent.notification.delivery.NotificationDeliveryClaimService;
import com.repoguard.agent.notification.delivery.NotificationDeliveryCompletionService;
import com.repoguard.agent.notification.delivery.NotificationDeliveryEventStateUpdater;
import com.repoguard.agent.notification.query.NotificationCandidateBindingQuery;
import com.repoguard.agent.notification.query.NotificationDeliverableEventQuery;
import com.repoguard.agent.notification.query.NotificationSuccessfulDeliveryQuery;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailureClassifier;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailurePolicy;
import com.repoguard.agent.notification.delivery.NotificationDeliveryLogContextFormatter;
import com.repoguard.agent.notification.delivery.NotificationDeliveryWorkerClock;
import com.repoguard.agent.notification.delivery.NotificationDeliveryWorkerMetricsRecorder;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import com.repoguard.agent.notification.retry.NotificationRetrySchedule;
import java.util.List;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDeliveryWorkerTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationDeliveryLogMapper deliveryLogMapper = org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationChannelAdapter adapter = org.mockito.Mockito.mock(NotificationChannelAdapter.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final NotificationDeliveryWorkerMetricsRecorder metricsRecorder =
        new NotificationDeliveryWorkerMetricsRecorder(
            new RabbitConsumeMetricsRecorderFactory(metrics),
            new NotificationDeliveryWorkerClock()
        );

    @Test
    void handleAcknowledgesMessageAndRecordsSuccessMetricAfterDelivery() throws Exception {
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        NotificationEventMessage message = message();

        worker().handle(message, channel, 99L);

        org.mockito.Mockito.verify(channel).basicAck(99L, false);
        org.mockito.Mockito.verify(metrics)
            .rabbitMessageConsumed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.Mockito.eq("success"),
                org.mockito.Mockito.eq("unknown")
            );
    }

    @Test
    void handleRejectsMessageWithoutRequeueAndRecordsRejectedMetricWhenDeliveryFails() throws Exception {
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        NotificationEventMessage message = message();
        when(eventMapper.selectById(11L)).thenThrow(new IllegalStateException("boom"));

        worker().handle(message, channel, 100L);

        org.mockito.Mockito.verify(channel).basicReject(100L, false);
        org.mockito.Mockito.verify(metrics)
            .rabbitMessageConsumed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.Mockito.eq("rejected"),
                org.mockito.Mockito.eq("notification_delivery_failed")
            );
    }

    @Test
    void handleRejectsFatalErrorExactlyOnceAndRethrowsIt() throws Exception {
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        NotificationEventMessage message = message();
        NotificationDeliveryWorkerMetricsRecorder recorder =
            org.mockito.Mockito.mock(NotificationDeliveryWorkerMetricsRecorder.class);
        AssertionError failure = new AssertionError("fatal");
        when(recorder.startedAt()).thenReturn(300L);
        when(recorder.elapsedMillis(300L)).thenReturn(9L);
        when(eventMapper.selectById(11L)).thenThrow(failure);

        assertThatThrownBy(() -> worker(recorder).handle(message, channel, 101L)).isSameAs(failure);
        verify(channel).basicReject(101L, false);
        verify(channel, never()).basicAck(101L, false);
        verify(recorder).recordConsumed(300L, "rejected", "notification_delivery_error");
    }

    @Test
    void telemetryFailureAfterAckDoesNotRejectAcknowledgedMessage() throws Exception {
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        NotificationEventMessage message = message();
        NotificationDeliveryWorkerMetricsRecorder recorder =
            org.mockito.Mockito.mock(NotificationDeliveryWorkerMetricsRecorder.class);
        when(recorder.startedAt()).thenReturn(400L);
        doThrow(new IllegalStateException("metrics unavailable"))
            .when(recorder)
            .recordConsumed(400L, "success");

        assertThatThrownBy(() -> worker(recorder).handle(message, channel, 102L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("metrics unavailable");
        verify(channel).basicAck(102L, false);
        verify(channel, never()).basicReject(102L, false);
    }

    @Test
    void deliversSameEventToMultipleRepositoryBindingsIndependently() throws Exception {
        when(adapter.provider()).thenReturn("DINGTALK");
        NotificationDeliveryWorker worker = worker();
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.success("request-1", "ok"));
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
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
        NotificationDeliveryWorker worker = worker();
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.failed("request-1", "timeout"));
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
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
        NotificationDeliveryWorker worker = worker();
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        when(bindingMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding(1L)));
        when(deliveryLogMapper.selectCount(any())).thenReturn(1L);

        worker.deliver(11L);

        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).send(any(), any());
        org.mockito.Mockito.verify(deliveryLogMapper, org.mockito.Mockito.never())
            .insert(any(NotificationDeliveryLog.class));
    }

    private NotificationEventMessage message() {
        return new NotificationEventMessage(
            11L,
            "notification-11",
            NotificationEventType.REVIEW_COMPLETED.code(),
            42L,
            7L
        );
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
                new NotificationDeliveryLogFactory(new NotificationTextLimiter(), new NotificationRetrySchedule()),
                new NotificationBindingMatcher(),
                new NotificationSuccessfulDeliveryQuery(deliveryLogMapper)
            )
        );
    }

    private NotificationDeliveryWorker worker() {
        return worker(metricsRecorder);
    }

    private NotificationDeliveryWorker worker(
        NotificationDeliveryWorkerMetricsRecorder workerMetricsRecorder
    ) {
        NotificationChannelAdapterRegistry registry =
            new NotificationChannelAdapterRegistry(List.of(adapter), new NotificationProviderKeyNormalizer());
        NotificationDeliveryEventStateUpdater eventStateUpdater =
            new NotificationDeliveryEventStateUpdater(eventMapper);
        return new NotificationDeliveryWorker(
            new NotificationDeliveryClaimService(
                new NotificationDeliverableEventQuery(eventMapper),
                eventStateUpdater,
                Clock.systemDefaultZone(),
                "test-delivery-worker"
            ),
            new NotificationEventPayloadParser(new ObjectMapper()),
            bindingBatchDeliveryService(registry),
            deliveryCompletionService(),
            workerMetricsRecorder,
            new NotificationDeliveryFailureClassifier(),
            new NotificationDeliveryLogContextFormatter()
        );
    }

    private NotificationDeliveryCompletionService deliveryCompletionService() {
        NotificationDeliveryEventStateUpdater eventStateUpdater =
            new NotificationDeliveryEventStateUpdater(eventMapper);
        return new NotificationDeliveryCompletionService(
            new NotificationDeliveryFailurePolicy(new NotificationRetrySchedule()),
            eventStateUpdater
        );
    }
}
