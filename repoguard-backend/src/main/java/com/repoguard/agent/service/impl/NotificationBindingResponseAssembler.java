package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    private boolean hasSecret(String encrypted) {
        return StringUtils.hasText(secretCryptoService.decrypt(encrypted));
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
