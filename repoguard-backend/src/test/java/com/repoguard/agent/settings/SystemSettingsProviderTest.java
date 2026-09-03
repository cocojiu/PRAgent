package com.repoguard.agent.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import com.repoguard.agent.tenancy.TenantContext;
import org.junit.jupiter.api.Test;

class SystemSettingsProviderTest {

    private final SystemSettingsConfigMapper systemSettingsConfigMapper = org.mockito.Mockito.mock(SystemSettingsConfigMapper.class);
    private final SystemSettingsProvider provider = new SystemSettingsProvider(systemSettingsConfigMapper);

    @Test
    void getSettingsReturnsStableSystemSettings() {
        SystemSettingsConfig config = new SystemSettingsConfig();
        config.setSystemName("RepoGuard");
        config.setLanguage("zh-CN");
        config.setTimezone("Asia/Shanghai");
        config.setRetentionDays(30);
        config.setMaxDiffLines(2000);
        config.setAutoComment(true);
        config.setAutoRetry(true);
        config.setGithubComment(true);
        config.setHighRiskPr(true);
        config.setFailedTask(true);
        config.setNotificationEmail("ops@example.com");
        config.setWebhookSignature(true);
        config.setSecretMasking(true);
        config.setPublicRepoAllowed(false);
        config.setTokenTtlDays(7);
        when(systemSettingsConfigMapper.selectByTenantId(1L)).thenReturn(config);

        SystemSettings settings = provider.getSettings();

        assertThat(settings.exists()).isTrue();
        assertThat(settings.systemName()).isEqualTo("RepoGuard");
        assertThat(settings.retentionDays()).isEqualTo(30);
        assertThat(settings.maxDiffLines()).isEqualTo(2000);
        assertThat(settings.notificationEmail()).isEqualTo("ops@example.com");
        assertThat(settings.tokenTtlDays()).isEqualTo(7);
    }

    @Test
    void getSettingsReturnsEmptySettingsWhenConfigurationIsMissing() {
        when(systemSettingsConfigMapper.selectByTenantId(1L)).thenReturn(null);

        SystemSettings settings = provider.getSettings();

        assertThat(settings.exists()).isFalse();
        assertThat(settings.retentionDays()).isNull();
        assertThat(settings.systemName()).isNull();
    }

    @Test
    void getSettingsLoadsConfigurationForActiveTenant() {
        SystemSettingsConfig config = new SystemSettingsConfig();
        config.setSystemName("Tenant 23 Guard");
        when(systemSettingsConfigMapper.selectByTenantId(23L)).thenReturn(config);

        SystemSettings settings;
        try (TenantContext.Scope _ = TenantContext.withTenant(23L)) {
            settings = provider.getSettings();
        }

        assertThat(settings.exists()).isTrue();
        assertThat(settings.systemName()).isEqualTo("Tenant 23 Guard");
    }
}
