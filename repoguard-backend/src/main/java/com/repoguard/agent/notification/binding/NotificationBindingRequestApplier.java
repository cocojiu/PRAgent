package com.repoguard.agent.notification.binding;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.external.OutboundCredentialPolicy;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.security.SecretUpdateValue;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Applies notification binding requests to entities, including secret inheritance and encryption.
 */
@Component
public class NotificationBindingRequestApplier {

    private final SecretCryptoService secretCryptoService;
    private final OutboundEndpointPolicy outboundEndpointPolicy;
    private final OutboundCredentialPolicy outboundCredentialPolicy;

    @Autowired
    public NotificationBindingRequestApplier(
        SecretCryptoService secretCryptoService,
        OutboundEndpointPolicy outboundEndpointPolicy,
        OutboundCredentialPolicy outboundCredentialPolicy
    ) {
        this.secretCryptoService = secretCryptoService;
        this.outboundEndpointPolicy = outboundEndpointPolicy;
        this.outboundCredentialPolicy = outboundCredentialPolicy;
    }

    public NotificationBindingRequestApplier(SecretCryptoService secretCryptoService) {
        this.secretCryptoService = secretCryptoService;
        this.outboundEndpointPolicy = null;
        this.outboundCredentialPolicy = null;
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
        String requestedWebhookUrl = submittedValue(request.webhookUrl()) ? request.webhookUrl().trim() : null;
        if (outboundEndpointPolicy != null && requestedWebhookUrl != null) {
            outboundEndpointPolicy.validateConfiguration(OutboundEndpointType.NOTIFICATION, requestedWebhookUrl);
        }
        if (outboundCredentialPolicy != null && requestedWebhookUrl != null) {
            String currentWebhookUrl = create ? null : decryptOrNull(binding.getWebhookUrlValue());
            outboundCredentialPolicy.requireFreshCredentialOnOriginChange(
                OutboundEndpointType.NOTIFICATION,
                currentWebhookUrl,
                requestedWebhookUrl,
                request.secret(),
                !create && StringUtils.hasText(binding.getSecretValue())
            );
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

    private boolean submittedValue(String value) {
        return StringUtils.hasText(value) && !value.trim().startsWith("****");
    }

    private String decryptOrNull(String encryptedValue) {
        if (!StringUtils.hasText(encryptedValue)) {
            return null;
        }
        try {
            return secretCryptoService.decrypt(encryptedValue);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
