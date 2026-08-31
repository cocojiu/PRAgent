package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.BaseSettingsDto;
import com.repoguard.agent.dto.NotificationSettingsDto;
import com.repoguard.agent.dto.ReviewPolicySettingsDto;
import com.repoguard.agent.dto.SecuritySettingsDto;
import com.repoguard.agent.dto.SettingLogDto;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import com.repoguard.agent.service.SystemSettingsApplicationService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SystemSettingsApplicationServiceImpl implements SystemSettingsApplicationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId LEGACY_DATE_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SUPPORTED_LANGUAGE = "中文";
    private static final int DEFAULT_CHUNK_FILE_THRESHOLD = 6;
    private static final int DEFAULT_CHUNK_LINE_THRESHOLD = 700;
    private static final int DEFAULT_CHUNK_MAX_FILES = 4;
    private static final int DEFAULT_CHUNK_MAX_LINES = 450;

    private final SystemSettingsConfigMapper systemSettingsConfigMapper;
    private final SystemSettingLogMapper systemSettingLogMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final CacheEvictionService cacheEvictionService;
    private final RabbitReviewQueueProperties reviewQueueProperties;

    public SystemSettingsApplicationServiceImpl(
        SystemSettingsConfigMapper systemSettingsConfigMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        CacheEvictionService cacheEvictionService,
        RabbitReviewQueueProperties reviewQueueProperties
    ) {
        this.systemSettingsConfigMapper = systemSettingsConfigMapper;
        this.systemSettingLogMapper = systemSettingLogMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.reviewQueueProperties = Objects.requireNonNull(reviewQueueProperties, "reviewQueueProperties");
    }

    @Override
    public SystemSettingsDto getSystemSettings() {
        return toSystemSettingsDto(loadSystemSettings(), loadReviewPolicy(), loadSettingLogs());
    }

    @Override
    @Transactional
    public SystemSettingsDto updateSystemSettings(SystemSettingsRequest request) {
        String timezone = normalizeTimezone(request.base().timezone());
        String language = normalizeLanguage(request.base().language());
        SystemSettingsConfig settingsConfig = loadSystemSettings();
        ReviewPolicyConfig reviewPolicyConfig = loadReviewPolicy();
        LocalDateTime now = LocalDateTime.now();

        settingsConfig.setSystemName(request.base().systemName().trim());
        settingsConfig.setLanguage(language);
        settingsConfig.setTimezone(timezone);
        settingsConfig.setRetentionDays(request.base().retentionDays());
        settingsConfig.setMaxDiffLines(request.policy().maxDiffLines());
        settingsConfig.setAutoComment(request.policy().autoComment());
        settingsConfig.setAutoRetry(request.policy().autoRetry());
        settingsConfig.setGithubComment(request.notification().githubComment());
        settingsConfig.setHighRiskPr(request.notification().highRiskPr());
        settingsConfig.setFailedTask(request.notification().failedTask());
        settingsConfig.setNotificationEmail(trimToNull(request.notification().email()));
        settingsConfig.setWebhookSignature(request.security().webhookSignature());
        settingsConfig.setSecretMasking(request.security().secretMasking());
        settingsConfig.setPublicRepoAllowed(request.security().publicRepoAllowed());
        settingsConfig.setTokenTtlDays(request.security().tokenTtlDays());
        settingsConfig.setUpdatedAt(now);
        systemSettingsConfigMapper.updateById(settingsConfig);

        reviewPolicyConfig.setTimeoutSeconds(request.policy().llmTimeoutSeconds());
        reviewPolicyConfig.setWorkerConcurrency(reviewQueueProperties.getWorkerConcurrency());
        reviewPolicyConfig.setUpdatedAt(now);
        reviewPolicyConfigMapper.updateById(reviewPolicyConfig);

        recordSystemSettingLog("admin", "更新系统设置", "成功", now);
        evictDashboardOverviewCompatibility();
        return toSystemSettingsDto(settingsConfig, reviewPolicyConfig, loadSettingLogs());
    }

    private void evictDashboardOverviewCompatibility() {
        cacheEvictionService.evictDashboardOverviewCompatibility();
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
        defaultConfig.setWorkerConcurrency(reviewQueueProperties.getWorkerConcurrency());
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

    private SystemSettingsConfig loadSystemSettings() {
        SystemSettingsConfig config = systemSettingsConfigMapper.selectById(1L);
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        SystemSettingsConfig defaultConfig = new SystemSettingsConfig();
        defaultConfig.setId(1L);
        defaultConfig.setSystemName("RepoGuard Agent");
        defaultConfig.setLanguage(SUPPORTED_LANGUAGE);
        defaultConfig.setTimezone("Asia/Shanghai");
        defaultConfig.setRetentionDays(90);
        defaultConfig.setMaxDiffLines(800);
        defaultConfig.setAutoComment(true);
        defaultConfig.setAutoRetry(true);
        defaultConfig.setGithubComment(true);
        defaultConfig.setHighRiskPr(true);
        defaultConfig.setFailedTask(true);
        defaultConfig.setNotificationEmail("ops@repoguard.dev");
        defaultConfig.setWebhookSignature(true);
        defaultConfig.setSecretMasking(true);
        defaultConfig.setPublicRepoAllowed(false);
        defaultConfig.setTokenTtlDays(30);
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        systemSettingsConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private List<SystemSettingLog> loadSettingLogs() {
        List<SystemSettingLog> logs = systemSettingLogMapper.selectList(
            new LambdaQueryWrapper<SystemSettingLog>()
                .orderByDesc(SystemSettingLog::getCreatedAt)
                .orderByDesc(SystemSettingLog::getId)
                .last("limit 20")
        );
        if (logs == null) {
            return List.of();
        }
        return logs;
    }

    private void recordSystemSettingLog(String operator, String action, String status, LocalDateTime createdAt) {
        SystemSettingLog log = new SystemSettingLog();
        log.setOperator(operator);
        log.setAction(action);
        log.setStatus(status);
        log.setCreatedAt(createdAt);
        systemSettingLogMapper.insert(log);
    }

    private SystemSettingsDto toSystemSettingsDto(
        SystemSettingsConfig settingsConfig,
        ReviewPolicyConfig reviewPolicyConfig,
        List<SystemSettingLog> logs
    ) {
        return new SystemSettingsDto(
            new BaseSettingsDto(
                settingsConfig.getSystemName(),
                settingsConfig.getLanguage(),
                validTimezoneOrDefault(settingsConfig.getTimezone()),
                settingsConfig.getRetentionDays()
            ),
            new ReviewPolicySettingsDto(
                settingsConfig.getMaxDiffLines(),
                reviewPolicyConfig.getTimeoutSeconds(),
                reviewQueueProperties.getWorkerConcurrency(),
                settingsConfig.getAutoComment(),
                settingsConfig.getAutoRetry()
            ),
            new NotificationSettingsDto(
                settingsConfig.getGithubComment(),
                settingsConfig.getHighRiskPr(),
                settingsConfig.getFailedTask(),
                settingsConfig.getNotificationEmail()
            ),
            new SecuritySettingsDto(
                settingsConfig.getWebhookSignature(),
                settingsConfig.getSecretMasking(),
                settingsConfig.getPublicRepoAllowed(),
                settingsConfig.getTokenTtlDays()
            ),
            logs.stream().map(this::toSettingLogDto).toList()
        );
    }

    private SettingLogDto toSettingLogDto(SystemSettingLog log) {
        return new SettingLogDto(
            format(log.getCreatedAt()),
            formatUtc(log.getCreatedAt()),
            log.getOperator(),
            log.getAction(),
            log.getStatus()
        );
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }

    private OffsetDateTime formatUtc(LocalDateTime time) {
        return time == null
            ? null
            : time.atZone(LEGACY_DATE_TIME_ZONE).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
    }

    private String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Timezone must be a valid IANA zone id");
        }
        String normalized = timezone.trim();
        try {
            ZoneId zoneId = ZoneId.of(normalized);
            if (!isIanaTimezone(normalized)) {
                throw new DateTimeException("Fixed-offset timezone is not an IANA region id");
            }
            return zoneId.getId();
        } catch (DateTimeException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Timezone must be a valid IANA zone id");
        }
    }

    private String validTimezoneOrDefault(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return LEGACY_DATE_TIME_ZONE.getId();
        }
        String normalized = timezone.trim();
        try {
            ZoneId zoneId = ZoneId.of(normalized);
            return isIanaTimezone(normalized) ? zoneId.getId() : LEGACY_DATE_TIME_ZONE.getId();
        } catch (DateTimeException exception) {
            return LEGACY_DATE_TIME_ZONE.getId();
        }
    }

    private boolean isIanaTimezone(String timezone) {
        return "UTC".equals(timezone) || ZoneId.getAvailableZoneIds().contains(timezone);
    }

    private String normalizeLanguage(String language) {
        String normalized = language == null ? "" : language.trim();
        if ("zh-CN".equalsIgnoreCase(normalized) || SUPPORTED_LANGUAGE.equals(normalized)) {
            return SUPPORTED_LANGUAGE;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Only zh-CN is currently supported");
    }

}
