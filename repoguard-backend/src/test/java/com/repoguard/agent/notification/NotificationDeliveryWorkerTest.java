package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
            eventMapper,
            bindingMapper,
            deliveryLogMapper,
            registry,
            new ObjectMapper()
        );
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.success("request-1", "ok"));
        NotificationEvent event = event();
        when(eventMapper.selectById(11L)).thenReturn(event);
        when(bindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(binding(1L), binding(2L)));
        when(deliveryLogMapper.selectCount(any())).thenReturn(0L);

        worker.deliver(11L);

        ArgumentCaptor<NotificationDeliveryLog> logCaptor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        org.mockito.Mockito.verify(deliveryLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues()).extracting(NotificationDeliveryLog::getBindingId).containsExactly(1L, 2L);
        assertThat(logCaptor.getAllValues()).extracting(NotificationDeliveryLog::getStatus).containsOnly("SUCCESS");
    }

    private NotificationEvent event() throws Exception {
        NotificationMessage message = new NotificationMessage(
            "REVIEW_COMPLETED",
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
        event.setEventType("REVIEW_COMPLETED");
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
}
