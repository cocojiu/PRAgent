package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.security.SecretValueView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Converts notification channel binding entities into API response DTOs.
 */
@Component
public class NotificationBindingResponseAssembler {

    static final String MASKED_SECRET = "******";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SecretCryptoService secretCryptoService;

    public NotificationBindingResponseAssembler(SecretCryptoService secretCryptoService) {
        this.secretCryptoService = secretCryptoService;
    }

    public NotificationBindingDto assemble(NotificationChannelBinding binding) {
        SecretValueView webhookUrl = SecretValueView.inspect(secretCryptoService, binding.getWebhookUrlValue());
        SecretValueView secret = SecretValueView.inspect(secretCryptoService, binding.getSecretValue());
        return new NotificationBindingDto(
            binding.getId(),
            binding.getName(),
            binding.getProvider(),
            binding.getOrganization(),
            binding.getRepository(),
            binding.getEnabled(),
            SecretValueView.STATUS_CONFIGURED.equals(webhookUrl.status()) ? MASKED_SECRET : null,
            SecretValueView.STATUS_CONFIGURED.equals(secret.status()) ? MASKED_SECRET : null,
            binding.getNotifyReviewCompleted(),
            binding.getNotifyReviewFailed(),
            binding.getNotifyHumanReviewRequired(),
            binding.getNotifyGithubComment(),
            binding.getStatus(),
            format(binding.getLastCheckedAt()),
            binding.getLastError(),
            format(binding.getCreatedAt()),
            format(binding.getUpdatedAt()),
            webhookUrl.status(),
            secret.status()
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
