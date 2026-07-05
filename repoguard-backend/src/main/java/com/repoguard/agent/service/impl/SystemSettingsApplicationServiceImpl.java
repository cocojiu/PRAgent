package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.CacheNames;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SystemSettingsApplicationServiceImpl implements SystemSettingsApplicationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_CHUNK_FILE_THRESHOLD = 6;
    private static final int DEFAULT_CHUNK_LINE_THRESHOLD = 700;
    private static final int DEFAULT_CHUNK_MAX_FILES = 4;
    private static final int DEFAULT_CHUNK_MAX_LINES = 450;

    private final SystemSettingsConfigMapper systemSettingsConfigMapper;
    private final SystemSettingLogMapper systemSettingLogMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;

    public SystemSettingsApplicationServiceImpl(
        SystemSettingsConfigMapper systemSettingsConfigMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper
    ) {
        this.systemSettingsConfigMapper = systemSettingsConfigMapper;
        this.systemSettingLogMapper = systemSettingLogMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
    }

    @Override
    public SystemSettingsDto getSystemSettings() {
        return toSystemSettingsDto(loadSystemSettings(), loadReviewPolicy(), loadSettingLogs());
    }

    @Override
    @Transactional
    @CacheEvict(
        cacheNames = {
            CacheNames.DASHBOARD_OVERVIEW,
            CacheNames.DASHBOARD_SUMMARY,
            CacheNames.DASHBOARD_REVIEW_TREND,
            CacheNames.DASHBOARD_RISK_DISTRIBUTION,
            CacheNames.DASHBOARD_RULES,
            CacheNames.DASHBOARD_HIGH_RISK_REVIEWS,
            CacheNames.DASHBOARD_LLM_QUALITY
        },
        allEntries = true
    )
    public SystemSettingsDto updateSystemSettings(SystemSettingsRequest request) {
        SystemSettingsConfig settingsConfig = loadSystemSettings();
        ReviewPolicyConfig reviewPolicyConfig = loadReviewPolicy();
        LocalDateTime now = LocalDateTime.now();

        settingsConfig.setSystemName(request.base().systemName().trim());
        settingsConfig.setLanguage(request.base().language().trim());
        settingsConfig.setTimezone(request.base().timezone().trim());
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
        reviewPolicyConfig.setWorkerConcurrency(request.policy().workerConcurrency());
        reviewPolicyConfig.setUpdatedAt(now);
        reviewPolicyConfigMapper.updateById(reviewPolicyConfig);

        recordSystemSettingLog("admin", "更新系统设置", "成功", now);
        return toSystemSettingsDto(settingsConfig, reviewPolicyConfig, loadSettingLogs());
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

    private SystemSettingsConfig loadSystemSettings() {
        SystemSettingsConfig config = systemSettingsConfigMapper.selectById(1L);
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        SystemSettingsConfig defaultConfig = new SystemSettingsConfig();
        defaultConfig.setId(1L);
        defaultConfig.setSystemName("RepoGuard Agent");
        defaultConfig.setLanguage("中文");
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
                settingsConfig.getTimezone(),
                settingsConfig.getRetentionDays()
            ),
            new ReviewPolicySettingsDto(
                settingsConfig.getMaxDiffLines(),
                reviewPolicyConfig.getTimeoutSeconds(),
                reviewPolicyConfig.getWorkerConcurrency(),
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
}
