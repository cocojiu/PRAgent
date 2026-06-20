package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.BaseSettingsRequest;
import com.repoguard.agent.dto.NotificationSettingsRequest;
import com.repoguard.agent.dto.ReviewPolicySettingsRequest;
import com.repoguard.agent.dto.SecuritySettingsRequest;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemSettingsApplicationServiceImplTest {

    private final SystemSettingsConfigMapper systemSettingsConfigMapper =
        org.mockito.Mockito.mock(SystemSettingsConfigMapper.class);
    private final SystemSettingLogMapper systemSettingLogMapper =
        org.mockito.Mockito.mock(SystemSettingLogMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper =
        org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SystemSettingsApplicationServiceImpl service = new SystemSettingsApplicationServiceImpl(
        systemSettingsConfigMapper,
        systemSettingLogMapper,
        reviewPolicyConfigMapper
    );

    @Test
    void getSystemSettingsCreatesDefaultsWhenMissing() {
        var result = service.getSystemSettings();

        assertThat(result.base().systemName()).isEqualTo("RepoGuard Agent");
        assertThat(result.base().language()).isEqualTo("中文");
        assertThat(result.policy().maxDiffLines()).isEqualTo(800);
        assertThat(result.policy().llmTimeoutSeconds()).isEqualTo(60);
        assertThat(result.notification().email()).isEqualTo("ops@repoguard.dev");
        assertThat(result.security().webhookSignature()).isTrue();
        assertThat(result.logs()).isEmpty();
        verify(systemSettingsConfigMapper).insert(any(SystemSettingsConfig.class));
        verify(reviewPolicyConfigMapper).insert(any(ReviewPolicyConfig.class));
    }

    @Test
    void updateSystemSettingsPersistsConfigAndRecordsLog() {
        SystemSettingsConfig settingsConfig = systemSettingsConfig();
        ReviewPolicyConfig reviewPolicyConfig = reviewPolicyConfig();
        when(systemSettingsConfigMapper.selectById(1L)).thenReturn(settingsConfig);
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig);
        when(systemSettingLogMapper.selectList(any())).thenReturn(List.of(settingLog()));

        var result = service.updateSystemSettings(request("devops@repoguard.dev"));

        assertThat(settingsConfig.getSystemName()).isEqualTo("RepoGuard Agent Pro");
        assertThat(settingsConfig.getRetentionDays()).isEqualTo(120);
        assertThat(settingsConfig.getMaxDiffLines()).isEqualTo(1200);
        assertThat(settingsConfig.getAutoRetry()).isFalse();
        assertThat(settingsConfig.getHighRiskPr()).isFalse();
        assertThat(settingsConfig.getNotificationEmail()).isEqualTo("devops@repoguard.dev");
        assertThat(settingsConfig.getPublicRepoAllowed()).isTrue();
        assertThat(settingsConfig.getTokenTtlDays()).isEqualTo(45);
        assertThat(reviewPolicyConfig.getTimeoutSeconds()).isEqualTo(90);
        assertThat(reviewPolicyConfig.getWorkerConcurrency()).isEqualTo(3);
        assertThat(result.policy().workerConcurrency()).isEqualTo(3);
        assertThat(result.logs()).hasSize(1);
        verify(systemSettingsConfigMapper).updateById(settingsConfig);
        verify(reviewPolicyConfigMapper).updateById(reviewPolicyConfig);

        ArgumentCaptor<SystemSettingLog> logCaptor = ArgumentCaptor.forClass(SystemSettingLog.class);
        verify(systemSettingLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getOperator()).isEqualTo("admin");
        assertThat(logCaptor.getValue().getAction()).isEqualTo("更新系统设置");
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("成功");
        assertThat(logCaptor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    void updateSystemSettingsNormalizesBlankEmailToNull() {
        SystemSettingsConfig settingsConfig = systemSettingsConfig();
        ReviewPolicyConfig reviewPolicyConfig = reviewPolicyConfig();
        when(systemSettingsConfigMapper.selectById(1L)).thenReturn(settingsConfig);
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig);

        var result = service.updateSystemSettings(request("   "));

        assertThat(settingsConfig.getNotificationEmail()).isNull();
        assertThat(result.notification().email()).isNull();
    }

    @Test
    void getSystemSettingsReturnsEmptyLogsWhenMapperReturnsNull() {
        when(systemSettingsConfigMapper.selectById(1L)).thenReturn(systemSettingsConfig());
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig());
        when(systemSettingLogMapper.selectList(any())).thenReturn(null);

        var result = service.getSystemSettings();

        assertThat(result.logs()).isEmpty();
    }

    private SystemSettingsRequest request(String email) {
        return new SystemSettingsRequest(
            new BaseSettingsRequest("RepoGuard Agent Pro", "中文", "Asia/Shanghai", 120),
            new ReviewPolicySettingsRequest(1200, 90, 3, true, false),
            new NotificationSettingsRequest(true, false, true, email),
            new SecuritySettingsRequest(true, true, true, 45)
        );
    }

    private SystemSettingsConfig systemSettingsConfig() {
        SystemSettingsConfig config = new SystemSettingsConfig();
        config.setId(1L);
        config.setSystemName("RepoGuard Agent");
        config.setLanguage("中文");
        config.setTimezone("Asia/Shanghai");
        config.setRetentionDays(90);
        config.setMaxDiffLines(800);
        config.setAutoComment(true);
        config.setAutoRetry(true);
        config.setGithubComment(true);
        config.setHighRiskPr(true);
        config.setFailedTask(true);
        config.setNotificationEmail("ops@repoguard.dev");
        config.setWebhookSignature(true);
        config.setSecretMasking(true);
        config.setPublicRepoAllowed(false);
        config.setTokenTtlDays(30);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private ReviewPolicyConfig reviewPolicyConfig() {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setTimeoutSeconds(60);
        config.setWorkerConcurrency(1);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private SystemSettingLog settingLog() {
        SystemSettingLog log = new SystemSettingLog();
        log.setId(1L);
        log.setOperator("admin");
        log.setAction("更新系统设置");
        log.setStatus("成功");
        log.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 30));
        return log;
    }
}
