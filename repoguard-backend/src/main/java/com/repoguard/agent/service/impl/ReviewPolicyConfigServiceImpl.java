package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.ReviewPolicyConfigService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReviewPolicyConfigServiceImpl implements ReviewPolicyConfigService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_CHUNK_FILE_THRESHOLD = 6;
    private static final int DEFAULT_CHUNK_LINE_THRESHOLD = 700;
    private static final int DEFAULT_CHUNK_MAX_FILES = 4;
    private static final int DEFAULT_CHUNK_MAX_LINES = 450;

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final SecretCryptoService secretCryptoService;

    public ReviewPolicyConfigServiceImpl(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public ReviewPolicyConfigDto getReviewPolicy() {
        return toReviewPolicyDto(loadReviewPolicy());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheNames.DASHBOARD_OVERVIEW, allEntries = true)
    public ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request) {
        ReviewPolicyConfig config = loadReviewPolicy();
        String apiKey = resolveSecretValue(secretCryptoService.decrypt(config.getApiKeyValue()), request.apiKey());
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
        config.setUpdatedAt(LocalDateTime.now());
        reviewPolicyConfigMapper.updateById(config);
        if (config.getApiKeyValue() == null) {
            reviewPolicyConfigMapper.update(
                new UpdateWrapper<ReviewPolicyConfig>()
                    .eq("id", config.getId())
                    .set("api_key_value", null)
            );
        }
        return toReviewPolicyDto(config);
    }

    private ReviewPolicyConfig loadReviewPolicy() {
        ReviewPolicyConfig config = reviewPolicyConfigMapper.selectById(1L);
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        ReviewPolicyConfig defaultConfig = new ReviewPolicyConfig();
        defaultConfig.setId(1L);
        defaultConfig.setLlmEnabled(true);
        defaultConfig.setLlmProvider("dashscope");
        defaultConfig.setModelName("qwen-plus");
        defaultConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        defaultConfig.setTimeoutSeconds(60);
        defaultConfig.setTemperature(BigDecimal.valueOf(0.20));
        defaultConfig.setMaxTokens(4096);
        defaultConfig.setFallbackToRules(true);
        defaultConfig.setWorkerConcurrency(1);
        defaultConfig.setChunkFileThreshold(DEFAULT_CHUNK_FILE_THRESHOLD);
        defaultConfig.setChunkLineThreshold(DEFAULT_CHUNK_LINE_THRESHOLD);
        defaultConfig.setChunkMaxFiles(DEFAULT_CHUNK_MAX_FILES);
        defaultConfig.setChunkMaxLines(DEFAULT_CHUNK_MAX_LINES);
        defaultConfig.setInputTokenPricePerMillion(BigDecimal.ZERO);
        defaultConfig.setOutputTokenPricePerMillion(BigDecimal.ZERO);
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        reviewPolicyConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private ReviewPolicyConfigDto toReviewPolicyDto(ReviewPolicyConfig config) {
        return new ReviewPolicyConfigDto(
            config.getLlmEnabled(),
            config.getLlmProvider(),
            config.getModelName(),
            config.getBaseUrl(),
            maskSecret(secretCryptoService.decrypt(config.getApiKeyValue())),
            config.getTimeoutSeconds(),
            config.getTemperature(),
            config.getMaxTokens(),
            config.getFallbackToRules(),
            config.getWorkerConcurrency(),
            valueOrDefault(config.getChunkFileThreshold(), DEFAULT_CHUNK_FILE_THRESHOLD),
            valueOrDefault(config.getChunkLineThreshold(), DEFAULT_CHUNK_LINE_THRESHOLD),
            valueOrDefault(config.getChunkMaxFiles(), DEFAULT_CHUNK_MAX_FILES),
            valueOrDefault(config.getChunkMaxLines(), DEFAULT_CHUNK_MAX_LINES),
            decimalOrZero(config.getInputTokenPricePerMillion()),
            decimalOrZero(config.getOutputTokenPricePerMillion()),
            format(config.getUpdatedAt())
        );
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

    private String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer valueOrDefault(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private BigDecimal decimalOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
