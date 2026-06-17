package com.repoguard.agent.config;

import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class SystemSettingsProvider {

    private static final long DEFAULT_SETTINGS_ID = 1L;

    private final SystemSettingsConfigMapper systemSettingsConfigMapper;

    public SystemSettingsProvider(SystemSettingsConfigMapper systemSettingsConfigMapper) {
        this.systemSettingsConfigMapper = systemSettingsConfigMapper;
    }

    public SystemSettings getSettings() {
        SystemSettingsConfig config = systemSettingsConfigMapper.selectById(DEFAULT_SETTINGS_ID);
        if (config == null) {
            return SystemSettings.empty();
        }
        return new SystemSettings(
            true,
            config.getSystemName(),
            config.getLanguage(),
            config.getTimezone(),
            config.getRetentionDays(),
            config.getMaxDiffLines(),
            config.getAutoComment(),
            config.getAutoRetry(),
            config.getGithubComment(),
            config.getHighRiskPr(),
            config.getFailedTask(),
            config.getNotificationEmail(),
            config.getWebhookSignature(),
            config.getSecretMasking(),
            config.getPublicRepoAllowed(),
            config.getTokenTtlDays()
        );
    }
}
