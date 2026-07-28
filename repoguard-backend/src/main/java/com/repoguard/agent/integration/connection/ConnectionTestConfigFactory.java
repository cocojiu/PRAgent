package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.external.OutboundCredentialPolicy;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

@Component
class ConnectionTestConfigFactory {

    private final SecretCryptoService secretCryptoService;
    private final OutboundEndpointPolicy endpointPolicy;
    private final OutboundCredentialPolicy credentialPolicy;

    ConnectionTestConfigFactory(SecretCryptoService secretCryptoService) {
        this(secretCryptoService, null, null);
    }

    @Autowired
    ConnectionTestConfigFactory(
        SecretCryptoService secretCryptoService,
        OutboundEndpointPolicy endpointPolicy,
        OutboundCredentialPolicy credentialPolicy
    ) {
        this.secretCryptoService = secretCryptoService;
        this.endpointPolicy = endpointPolicy;
        this.credentialPolicy = credentialPolicy;
    }

    IntegrationConfig githubIntegrationForTest(
        String provider,
        GithubIntegrationConfigRequest request,
        IntegrationConfig savedConfig
    ) {
        if (endpointPolicy != null) {
            endpointPolicy.validateConfiguration(OutboundEndpointType.GITHUB, request.baseUrl());
            credentialPolicy.requireFreshCredentialOnOriginChange(
                OutboundEndpointType.GITHUB,
                savedConfig == null ? null : savedConfig.getBaseUrl(),
                request.baseUrl(),
                request.token(),
                savedConfig != null && StringUtils.hasText(savedConfig.getTokenValue())
            );
        }
        IntegrationConfig config = new IntegrationConfig();
        String token = resolveSecretValue(
            savedConfig == null ? null : decryptSavedSecret(savedConfig.getTokenValue()),
            request.token()
        );
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(secretCryptoService.encrypt(token));
        config.setDefaultOwner(trimToNull(request.defaultOwner()));
        config.setDefaultRepo(trimToNull(request.defaultRepo()));
        return config;
    }

    IntegrationConfig serviceIntegrationForTest(
        String provider,
        ServiceIntegrationConfigRequest request,
        IntegrationConfig savedConfig
    ) {
        OutboundEndpointType type = "MYSQL".equalsIgnoreCase(provider)
            ? OutboundEndpointType.MYSQL
            : OutboundEndpointType.RABBITMQ;
        if (endpointPolicy != null) {
            endpointPolicy.validateConfiguration(type, request.baseUrl());
            credentialPolicy.requireFreshCredentialOnOriginChange(
                type,
                savedConfig == null ? null : savedConfig.getBaseUrl(),
                request.baseUrl(),
                request.secret(),
                savedConfig != null && StringUtils.hasText(savedConfig.getTokenValue())
            );
        }
        IntegrationConfig config = new IntegrationConfig();
        String secret = resolveSecretValue(
            savedConfig == null ? null : decryptSavedSecret(savedConfig.getTokenValue()),
            request.secret()
        );
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(secretCryptoService.encrypt(secret));
        config.setDefaultOwner(trimToNull(request.username()));
        config.setDefaultRepo(trimToNull(request.resource()));
        return config;
    }

    ReviewPolicyConfig reviewPolicyForTest(ReviewPolicyConfigRequest request, ReviewPolicyConfig savedConfig) {
        if (endpointPolicy != null && Boolean.TRUE.equals(request.llmEnabled())) {
            endpointPolicy.validateConfiguration(OutboundEndpointType.LLM, request.baseUrl());
            credentialPolicy.requireFreshCredentialOnOriginChange(
                OutboundEndpointType.LLM,
                savedConfig == null ? null : savedConfig.getBaseUrl(),
                request.baseUrl(),
                request.apiKey(),
                savedConfig != null && StringUtils.hasText(savedConfig.getApiKeyValue())
            );
        }
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        String apiKey = resolveSecretValue(
            savedConfig == null ? null : decryptSavedSecret(savedConfig.getApiKeyValue()),
            request.apiKey()
        );
        config.setLlmEnabled(request.llmEnabled());
        config.setLlmProvider(request.llmProvider().trim());
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(trimToNull(request.baseUrl()));
        config.setApiKeyValue(secretCryptoService.encrypt(apiKey));
        config.setTimeoutSeconds(request.timeoutSeconds());
        config.setTemperature(request.temperature());
        config.setMaxTokens(request.maxTokens());
        config.setFallbackToRules(request.fallbackToRules());
        config.setWorkerConcurrency(request.workerConcurrency());
        config.setChunkFileThreshold(request.chunkFileThreshold());
        config.setChunkLineThreshold(request.chunkLineThreshold());
        config.setChunkMaxFiles(request.chunkMaxFiles());
        config.setChunkMaxLines(request.chunkMaxLines());
        config.setInputTokenPricePerMillion(request.inputTokenPricePerMillion());
        config.setOutputTokenPricePerMillion(request.outputTokenPricePerMillion());
        return config;
    }

    private String resolveSecretValue(String currentValue, String submittedValue) {
        if (submittedValue == null) {
            return currentValue;
        }
        String trimmed = submittedValue.trim();
        if (trimmed.startsWith("****")) {
            return currentValue;
        }
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String decryptSavedSecret(String encryptedValue) {
        if (!StringUtils.hasText(encryptedValue)) {
            return null;
        }
        try {
            return secretCryptoService.decrypt(encryptedValue);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
