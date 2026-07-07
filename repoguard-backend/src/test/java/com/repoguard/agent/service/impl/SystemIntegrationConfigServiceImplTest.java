package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class SystemIntegrationConfigServiceImplTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final MockEnvironment environment = new MockEnvironment()
        .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/repoguard?useSSL=false")
        .withProperty("spring.datasource.username", "repo_user")
        .withProperty("spring.datasource.password", "mysql-default-secret")
        .withProperty("spring.rabbitmq.host", "mq.example.com")
        .withProperty("spring.rabbitmq.port", "5673")
        .withProperty("spring.rabbitmq.username", "mq_user")
        .withProperty("spring.rabbitmq.password", "mq-default-secret")
        .withProperty("spring.rabbitmq.virtual-host", "repoguard-vhost");
    private final CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
    private final SystemIntegrationConfigServiceImpl service = new SystemIntegrationConfigServiceImpl(
        integrationConfigMapper,
        secretCryptoService,
        environment,
        cacheEvictionService
    );

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new SystemIntegrationConfigServiceImpl(
            integrationConfigMapper,
            secretCryptoService,
            environment,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    @Test
    void getMysqlIntegrationCreatesDefaultsFromEnvironment() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

        var result = service.getMysqlIntegration();

        assertThat(result.provider()).isEqualTo("MYSQL");
        assertThat(result.status()).isEqualTo("not_configured");
        assertThat(result.baseUrl()).isEqualTo("jdbc:mysql://db.example.com:3306/repoguard?useSSL=false");
        assertThat(result.username()).isEqualTo("repo_user");
        assertThat(result.resource()).isEqualTo("repoguard");
        assertThat(result.secret()).isEqualTo("****cret");

        ArgumentCaptor<IntegrationConfig> configCaptor = ArgumentCaptor.forClass(IntegrationConfig.class);
        verify(integrationConfigMapper).insert(configCaptor.capture());
        IntegrationConfig inserted = configCaptor.getValue();
        assertThat(inserted.getProvider()).isEqualTo("MYSQL");
        assertThat(secretCryptoService.decrypt(inserted.getTokenValue())).isEqualTo("mysql-default-secret");
    }

    @Test
    void updateGithubIntegrationStoresNewTokenAndEvictsDashboardOverview() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "ghp_new_secret_1234",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("ghp_new_secret_1234");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(config.getLastError()).isNull();
        assertThat(result.token()).isEqualTo("****1234");
        verify(integrationConfigMapper).updateById(config);
        verify(cacheEvictionService).evictDashboardOverview();
    }

    @Test
    void updateGithubIntegrationClearsTokenWhenBlankValueIsSubmitted() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).isNull();
        assertThat(config.getStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.token()).isNull();
        verify(integrationConfigMapper).updateById(config);
        verify(integrationConfigMapper, org.mockito.Mockito.times(2)).update(any(UpdateWrapper.class));
        verify(cacheEvictionService).evictDashboardOverview();
    }

    @Test
    void getGithubIntegrationReportsKeyMismatchWithoutDecryptingSecret() {
        IntegrationConfig config = githubConfig("enc:v2:old-key:not-a-real-payload");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.getGithubIntegration();

        assertThat(result.token()).isNull();
        assertThat(result.secretStatus()).isEqualTo("key_mismatch");
    }

    @Test
    void getMysqlIntegrationReportsDecryptFailedWithoutBreakingConfigPage() {
        IntegrationConfig config = serviceConfig("MYSQL", "enc:v2:local:not-a-real-payload");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.getMysqlIntegration();

        assertThat(result.secret()).isNull();
        assertThat(result.secretStatus()).isEqualTo("decrypt_failed");
    }

    @Test
    void updateGithubIntegrationCanReplaceDamagedExistingTokenWithoutDecryptingIt() {
        IntegrationConfig config = githubConfig("enc:v2:local:not-a-real-payload");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "ghp_repaired_secret_1234",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("ghp_repaired_secret_1234");
        assertThat(result.token()).isEqualTo("****1234");
        assertThat(result.secretStatus()).isEqualTo("configured");
        verify(integrationConfigMapper).updateById(config);
    }

    @Test
    void updateGithubIntegrationPreservesDamagedExistingTokenWhenMaskedValueIsSubmitted() {
        IntegrationConfig config = githubConfig("enc:v2:local:not-a-real-payload");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "****",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).isEqualTo("enc:v2:local:not-a-real-payload");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(result.token()).isNull();
        assertThat(result.secretStatus()).isEqualTo("decrypt_failed");
        verify(integrationConfigMapper).updateById(config);
        verify(cacheEvictionService).evictDashboardOverview();
    }

    @Test
    void updateMysqlIntegrationKeepsExistingSecretWhenMaskedValueIsSubmitted() {
        IntegrationConfig config = serviceConfig("MYSQL", "mysql-existing-1234");
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        var result = service.updateMysqlIntegration(new ServiceIntegrationConfigRequest(
            "jdbc:mysql://localhost:3306/repoguard",
            "root",
            "****1234",
            "repoguard"
        ));

        assertThat(config.getTokenValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("mysql-existing-1234");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(result.secret()).isEqualTo("****1234");
        verify(integrationConfigMapper).updateById(config);
    }

    @Test
    void getRabbitMqIntegrationCreatesDefaultsFromEnvironment() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

        var result = service.getRabbitMqIntegration();

        assertThat(result.provider()).isEqualTo("RABBITMQ");
        assertThat(result.baseUrl()).isEqualTo("amqp://mq.example.com:5673");
        assertThat(result.username()).isEqualTo("mq_user");
        assertThat(result.resource()).isEqualTo("repoguard-vhost");
        assertThat(result.secret()).isEqualTo("****cret");
    }

    private IntegrationConfig githubConfig(String token) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(token);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private IntegrationConfig serviceConfig(String provider, String secret) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(2L);
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl("MYSQL".equals(provider) ? "jdbc:mysql://localhost:3306/repoguard" : "amqp://localhost:5672");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("MYSQL".equals(provider) ? "repoguard" : "/");
        config.setTokenValue(secret);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }
}
