package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.security.SecretUpdateValue;
import com.repoguard.agent.security.SecretValueView;
import com.repoguard.agent.service.SystemIntegrationConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SystemIntegrationConfigServiceImpl implements SystemIntegrationConfigService {

    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final String MYSQL_PROVIDER = "MYSQL";
    private static final String RABBITMQ_PROVIDER = "RABBITMQ";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IntegrationConfigMapper integrationConfigMapper;
    private final SecretCryptoService secretCryptoService;
    private final Environment environment;
    private final CacheEvictionService cacheEvictionService;

    public SystemIntegrationConfigServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        SecretCryptoService secretCryptoService,
        Environment environment,
        CacheEvictionService cacheEvictionService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.secretCryptoService = secretCryptoService;
        this.environment = environment;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
    }

    @Override
    public GithubIntegrationConfigDto getGithubIntegration() {
        return toGithubDto(loadGithubConfig());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheNames.GITHUB_OPEN_PULL_REQUESTS, allEntries = true)
    public GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request) {
        IntegrationConfig config = loadGithubConfig();
        SecretUpdateValue token = SecretUpdateValue.resolve(secretCryptoService, config.getTokenValue(), request.token());
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(token.encryptedValue());
        config.setDefaultOwner(trimToNull(request.defaultOwner()));
        config.setDefaultRepo(trimToNull(request.defaultRepo()));
        config.setStatus(token.configured() ? "CONFIGURED" : "NOT_CONFIGURED");
        config.setLastError(null);
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (config.getTokenValue() == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("token_value", null)
            );
        }
        integrationConfigMapper.update(
            new UpdateWrapper<IntegrationConfig>()
                .eq("id", config.getId())
                .set("last_error", null)
        );
        evictDashboardOverview();
        return toGithubDto(config);
    }

    @Override
    public ServiceIntegrationConfigDto getMysqlIntegration() {
        return toServiceIntegrationDto(loadServiceIntegration(MYSQL_PROVIDER));
    }

    @Override
    @Transactional
    public ServiceIntegrationConfigDto updateMysqlIntegration(ServiceIntegrationConfigRequest request) {
        return updateServiceIntegration(MYSQL_PROVIDER, request);
    }

    @Override
    public ServiceIntegrationConfigDto getRabbitMqIntegration() {
        return toServiceIntegrationDto(loadServiceIntegration(RABBITMQ_PROVIDER));
    }

    @Override
    @Transactional
    public ServiceIntegrationConfigDto updateRabbitMqIntegration(ServiceIntegrationConfigRequest request) {
        return updateServiceIntegration(RABBITMQ_PROVIDER, request);
    }

    private void evictDashboardOverview() {
        cacheEvictionService.evictDashboardOverview();
    }

    private IntegrationConfig loadGithubConfig() {
        IntegrationConfig config = findIntegration(GITHUB_PROVIDER);
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

    private ServiceIntegrationConfigDto updateServiceIntegration(String provider, ServiceIntegrationConfigRequest request) {
        IntegrationConfig config = loadServiceIntegration(provider);
        SecretUpdateValue secret = SecretUpdateValue.resolve(secretCryptoService, config.getTokenValue(), request.secret());
        config.setBaseUrl(request.baseUrl().trim());
        config.setDefaultOwner(trimToNull(request.username()));
        config.setDefaultRepo(trimToNull(request.resource()));
        config.setTokenValue(secret.encryptedValue());
        config.setStatus(StringUtils.hasText(config.getBaseUrl()) ? "CONFIGURED" : "NOT_CONFIGURED");
        config.setLastError(null);
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (config.getTokenValue() == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("token_value", null)
            );
        }
        integrationConfigMapper.update(
            new UpdateWrapper<IntegrationConfig>()
                .eq("id", config.getId())
                .set("last_error", null)
        );
        return toServiceIntegrationDto(config);
    }

    private IntegrationConfig loadServiceIntegration(String provider) {
        IntegrationConfig config = findIntegration(provider);
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        IntegrationConfig defaultConfig = new IntegrationConfig();
        defaultConfig.setProvider(provider);
        defaultConfig.setStatus("NOT_CONFIGURED");
        defaultConfig.setBaseUrl(defaultServiceBaseUrl(provider));
        defaultConfig.setDefaultOwner(defaultServiceUsername(provider));
        defaultConfig.setDefaultRepo(defaultServiceResource(provider));
        defaultConfig.setTokenValue(secretCryptoService.encrypt(defaultServiceSecret(provider)));
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        integrationConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private IntegrationConfig findIntegration(String provider) {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, provider)
        );
    }

    private String defaultServiceBaseUrl(String provider) {
        if (MYSQL_PROVIDER.equals(provider)) {
            return property(
                "spring.datasource.url",
                "jdbc:mysql://localhost:3306/repoguard?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
            );
        }
        if (RABBITMQ_PROVIDER.equals(provider)) {
            return "amqp://" + property("spring.rabbitmq.host", "localhost") + ":" + property("spring.rabbitmq.port", "5672");
        }
        return "";
    }

    private String defaultServiceUsername(String provider) {
        if (MYSQL_PROVIDER.equals(provider)) {
            return property("spring.datasource.username", "root");
        }
        if (RABBITMQ_PROVIDER.equals(provider)) {
            return property("spring.rabbitmq.username", "guest");
        }
        return null;
    }

    private String defaultServiceSecret(String provider) {
        if (MYSQL_PROVIDER.equals(provider)) {
            return property("spring.datasource.password", null);
        }
        if (RABBITMQ_PROVIDER.equals(provider)) {
            return property("spring.rabbitmq.password", null);
        }
        return null;
    }

    private String defaultServiceResource(String provider) {
        if (MYSQL_PROVIDER.equals(provider)) {
            return databaseNameFromJdbcUrl(defaultServiceBaseUrl(provider));
        }
        if (RABBITMQ_PROVIDER.equals(provider)) {
            return property("spring.rabbitmq.virtual-host", "/");
        }
        return null;
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl)) {
            return null;
        }
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0 || slash == jdbcUrl.length() - 1) {
            return null;
        }
        String database = jdbcUrl.substring(slash + 1);
        int query = database.indexOf('?');
        return query >= 0 ? database.substring(0, query) : database;
    }

    private GithubIntegrationConfigDto toGithubDto(IntegrationConfig config) {
        SecretValueView secret = SecretValueView.inspect(secretCryptoService, config.getTokenValue());
        return new GithubIntegrationConfigDto(
            config.getProvider(),
            lower(config.getStatus()),
            config.getBaseUrl(),
            secret.maskedValue(),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            format(config.getLastCheckedAt()),
            config.getLastError(),
            format(config.getUpdatedAt()),
            secret.status()
        );
    }

    private ServiceIntegrationConfigDto toServiceIntegrationDto(IntegrationConfig config) {
        SecretValueView secret = SecretValueView.inspect(secretCryptoService, config.getTokenValue());
        return new ServiceIntegrationConfigDto(
            config.getProvider(),
            lower(config.getStatus()),
            config.getBaseUrl(),
            config.getDefaultOwner(),
            secret.maskedValue(),
            config.getDefaultRepo(),
            format(config.getLastCheckedAt()),
            config.getLastError(),
            format(config.getUpdatedAt()),
            secret.status()
        );
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String property(String key, String defaultValue) {
        return environment == null ? defaultValue : environment.getProperty(key, defaultValue);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
