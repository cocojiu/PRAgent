package com.repoguard.agent.settings;

import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import com.repoguard.agent.tenancy.TenantContext;
import org.springframework.stereotype.Service;

@Service
public class SystemSettingsProvider {

    private final SystemSettingsConfigMapper systemSettingsConfigMapper;

    public SystemSettingsProvider(SystemSettingsConfigMapper systemSettingsConfigMapper) {
        this.systemSettingsConfigMapper = systemSettingsConfigMapper;
    }

    public SystemSettings getSettings() {
        SystemSettingsConfig config = systemSettingsConfigMapper.selectByTenantId(
            TenantContext.currentTenantIdOrDefault()
        );
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
