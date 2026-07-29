package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.binding.NotificationBindingStatus;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Applies notification binding connection test results to bindings and response DTOs.
 */
@Component
public class NotificationBindingConnectionTestResultApplier {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ERROR_LENGTH = 1024;

    public ConnectionTestResultDto apply(
        NotificationChannelBinding binding,
        NotificationSendResult result,
        LocalDateTime checkedAt
    ) {
        binding.setLastCheckedAt(checkedAt);
        binding.setStatus(result.success()
            ? NotificationBindingStatus.CONNECTED.code()
            : NotificationBindingStatus.FAILED.code());
        binding.setLastError(result.success() ? null : truncate(result.message(), MAX_ERROR_LENGTH));
        binding.setUpdatedAt(checkedAt);
        return new ConnectionTestResultDto(
            result.success(),
            result.success() ? "connected" : "failed",
            result.message(),
            format(binding.getLastCheckedAt())
        );
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
