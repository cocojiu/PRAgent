package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.binding.NotificationBindingStatus;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationBindingConnectionTestResultApplierTest {

    private final NotificationBindingConnectionTestResultApplier applier =
        new NotificationBindingConnectionTestResultApplier();

    @Test
    void applySuccessMarksConnectedClearsErrorAndBuildsResponse() {
        NotificationChannelBinding binding = binding();
        binding.setLastError("old error");

        var response = applier.apply(
            binding,
            NotificationSendResult.success("request-1", "ok"),
            checkedAt()
        );

        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.CONNECTED.code());
        assertThat(binding.getLastError()).isNull();
        assertThat(binding.getLastCheckedAt()).isEqualTo(checkedAt());
        assertThat(binding.getUpdatedAt()).isEqualTo(checkedAt());
        assertThat(response.success()).isTrue();
        assertThat(response.status()).isEqualTo("connected");
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.checkedAt()).isEqualTo("2026-06-20 10:05:00");
    }

    @Test
    void applyFailureMarksFailedStoresTruncatedErrorAndBuildsResponse() {
        NotificationChannelBinding binding = binding();
        String longMessage = "x".repeat(1100);

        var response = applier.apply(
            binding,
            NotificationSendResult.failed("request-1", longMessage),
            checkedAt()
        );

        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.FAILED.code());
        assertThat(binding.getLastError()).hasSize(1024);
        assertThat(response.success()).isFalse();
        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.message()).isEqualTo(longMessage);
        assertThat(response.checkedAt()).isEqualTo("2026-06-20 10:05:00");
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        return binding;
    }

    private LocalDateTime checkedAt() {
        return LocalDateTime.of(2026, 6, 20, 10, 5);
    }
}
