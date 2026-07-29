package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.query.NotificationCandidateBindingQuery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationBindingBatchDeliveryServiceTest {

    private final NotificationCandidateBindingQuery candidateBindingQuery =
        org.mockito.Mockito.mock(NotificationCandidateBindingQuery.class);
    private final NotificationBindingDeliveryService bindingDeliveryService =
        org.mockito.Mockito.mock(NotificationBindingDeliveryService.class);
    private final NotificationBindingBatchDeliveryService service =
        new NotificationBindingBatchDeliveryService(candidateBindingQuery, bindingDeliveryService);

    @Test
    void deliversAllCandidateBindingsAndSummarizesActualResults() {
        NotificationEvent event = event();
        NotificationMessage message = message();
        NotificationChannelBinding firstBinding = binding(1L);
        NotificationChannelBinding secondBinding = binding(2L);
        NotificationChannelBinding skippedBinding = binding(3L);
        when(candidateBindingQuery.load(message))
            .thenReturn(List.of(firstBinding, secondBinding, skippedBinding));
        when(bindingDeliveryService.deliver(event, message, firstBinding))
            .thenReturn(Optional.of(NotificationSendResult.success("request-1", "ok")));
        when(bindingDeliveryService.deliver(event, message, secondBinding))
            .thenReturn(Optional.of(NotificationSendResult.failed("request-2", "timeout")));
        when(bindingDeliveryService.deliver(event, message, skippedBinding))
            .thenReturn(Optional.empty());

        NotificationDeliveryResultSummary result = service.deliver(event, message);

        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.anyFailed()).isTrue();
    }

    @Test
    void returnsEmptySummaryWhenThereAreNoCandidateBindings() {
        NotificationEvent event = event();
        NotificationMessage message = message();
        when(candidateBindingQuery.load(message)).thenReturn(List.of());

        NotificationDeliveryResultSummary result = service.deliver(event, message);

        assertThat(result.attemptedCount()).isZero();
        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.anyFailed()).isFalse();
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setTaskId(42L);
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setRetryCount(0);
        return event;
    }

    private NotificationMessage message() {
        return new NotificationMessage(
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
    }

    private NotificationChannelBinding binding(Long id) {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(id);
        binding.setProvider("DINGTALK");
        binding.setEnabled(true);
        binding.setNotifyReviewCompleted(true);
        return binding;
    }
}
