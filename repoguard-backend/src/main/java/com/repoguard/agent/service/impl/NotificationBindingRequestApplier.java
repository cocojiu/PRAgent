package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies notification binding requests to entities, including secret inheritance and encryption.
 */
@Component
public class NotificationBindingRequestApplier {

    private static final String MASKED_SECRET = NotificationBindingResponseAssembler.MASKED_SECRET;

    private final SecretCryptoService secretCryptoService;

    public NotificationBindingRequestApplier(SecretCryptoService secretCryptoService) {
        this.secretCryptoService = secretCryptoService;
    }

    public void apply(
        NotificationChannelBinding binding,
        NotificationBindingRequest request,
        LocalDateTime now,
        boolean create
    ) {
        binding.setName(request.name().trim());
        binding.setProvider(normalizeProvider(request.provider()));
        binding.setOrganization(request.organization().trim());
        binding.setRepository(request.repository().trim());
        binding.setEnabled(Boolean.TRUE.equals(request.enabled()));
        String existingWebhookUrl = create ? null : secretCryptoService.decrypt(binding.getWebhookUrlValue());
        String existingSecret = create ? null : secretCryptoService.decrypt(binding.getSecretValue());
        String webhookUrl = resolveSecret(existingWebhookUrl, request.webhookUrl());
        if (!StringUtils.hasText(webhookUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Webhook URL is required");
        }
        binding.setWebhookUrlValue(secretCryptoService.encrypt(webhookUrl));
        binding.setSecretValue(secretCryptoService.encrypt(resolveSecret(existingSecret, request.secret())));
        binding.setNotifyReviewCompleted(request.notifyReviewCompleted());
        binding.setNotifyReviewFailed(request.notifyReviewFailed());
        binding.setNotifyHumanReviewRequired(request.notifyHumanReviewRequired());
        binding.setNotifyGithubComment(request.notifyGithubComment());
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        binding.setLastError(null);
        binding.setUpdatedAt(now);
    }

    private String resolveSecret(String existing, String requested) {
        if (MASKED_SECRET.equals(requested)) {
            return existing;
        }
        return trim(requested);
    }

    private String normalizeProvider(String provider) {
        return trim(provider).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
