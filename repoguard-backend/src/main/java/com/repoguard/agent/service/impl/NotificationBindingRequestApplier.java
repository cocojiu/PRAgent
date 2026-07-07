package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.security.SecretUpdateValue;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies notification binding requests to entities, including secret inheritance and encryption.
 */
@Component
public class NotificationBindingRequestApplier {

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
        SecretUpdateValue webhookUrl = SecretUpdateValue.resolve(
            secretCryptoService,
            create ? null : binding.getWebhookUrlValue(),
            request.webhookUrl()
        );
        if (!webhookUrl.configured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Webhook URL is required");
        }
        binding.setWebhookUrlValue(webhookUrl.encryptedValue());
        binding.setSecretValue(SecretUpdateValue.resolve(
            secretCryptoService,
            create ? null : binding.getSecretValue(),
            request.secret()
        ).encryptedValue());
        binding.setNotifyReviewCompleted(request.notifyReviewCompleted());
        binding.setNotifyReviewFailed(request.notifyReviewFailed());
        binding.setNotifyHumanReviewRequired(request.notifyHumanReviewRequired());
        binding.setNotifyGithubComment(request.notifyGithubComment());
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        binding.setLastError(null);
        binding.setUpdatedAt(now);
    }

    private String normalizeProvider(String provider) {
        return trim(provider).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
