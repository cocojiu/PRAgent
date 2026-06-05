package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.service.SystemConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;

    public SystemConfigServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
    }

    @Override
    public GithubIntegrationConfigDto getGithubIntegration() {
        return toGithubDto(loadGithubConfig());
    }

    @Override
    @Transactional
    public GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request) {
        IntegrationConfig config = loadGithubConfig();
        config.setBaseUrl(request.baseUrl().trim());
        if (shouldReplaceSecret(request.token())) {
            config.setTokenValue(request.token().trim());
        }
        config.setDefaultOwner(trimToNull(request.defaultOwner()));
        config.setDefaultRepo(trimToNull(request.defaultRepo()));
        config.setStatus(StringUtils.hasText(config.getTokenValue()) ? "CONFIGURED" : "NOT_CONFIGURED");
        config.setLastError(null);
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        return toGithubDto(config);
    }

    @Override
    public ReviewPolicyConfigDto getReviewPolicy() {
        return toReviewPolicyDto(loadReviewPolicy());
    }

    @Override
    @Transactional
    public ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request) {
        ReviewPolicyConfig config = loadReviewPolicy();
        config.setLlmEnabled(request.llmEnabled());
        config.setLlmProvider(request.llmProvider().trim());
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(trimToNull(request.baseUrl()));
        if (shouldReplaceSecret(request.apiKey())) {
            config.setApiKeyValue(request.apiKey().trim());
        }
        config.setTimeoutSeconds(request.timeoutSeconds());
        config.setTemperature(request.temperature());
        config.setMaxTokens(request.maxTokens());
        config.setFallbackToRules(request.fallbackToRules());
        config.setWorkerConcurrency(request.workerConcurrency());
        config.setUpdatedAt(LocalDateTime.now());
        reviewPolicyConfigMapper.updateById(config);
        return toReviewPolicyDto(config);
    }

    private IntegrationConfig loadGithubConfig() {
        IntegrationConfig config = integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        IntegrationConfig defaultConfig = new IntegrationConfig();
        defaultConfig.setProvider(GITHUB_PROVIDER);
        defaultConfig.setStatus("NOT_CONFIGURED");
        defaultConfig.setBaseUrl("https://api.github.com");
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        integrationConfigMapper.insert(defaultConfig);
        return defaultConfig;
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
        defaultConfig.setTemperature(java.math.BigDecimal.valueOf(0.20));
        defaultConfig.setMaxTokens(4096);
        defaultConfig.setFallbackToRules(true);
        defaultConfig.setWorkerConcurrency(1);
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        reviewPolicyConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private GithubIntegrationConfigDto toGithubDto(IntegrationConfig config) {
        return new GithubIntegrationConfigDto(
            config.getProvider(),
            lower(config.getStatus()),
            config.getBaseUrl(),
            maskSecret(config.getTokenValue()),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            format(config.getLastCheckedAt()),
            config.getLastError(),
            format(config.getUpdatedAt())
        );
    }

    private ReviewPolicyConfigDto toReviewPolicyDto(ReviewPolicyConfig config) {
        return new ReviewPolicyConfigDto(
            config.getLlmEnabled(),
            config.getLlmProvider(),
            config.getModelName(),
            config.getBaseUrl(),
            maskSecret(config.getApiKeyValue()),
            config.getTimeoutSeconds(),
            config.getTemperature(),
            config.getMaxTokens(),
            config.getFallbackToRules(),
            config.getWorkerConcurrency(),
            format(config.getUpdatedAt())
        );
    }

    private boolean shouldReplaceSecret(String value) {
        return StringUtils.hasText(value) && !value.trim().startsWith("****");
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

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
