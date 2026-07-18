package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ApplicationProfileConfigurationTest {

    @Test
    void baseConfigurationHasNoImplicitProfileOrLocalInfrastructureDefaults() throws IOException {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.profiles.active")).isNull();
        assertThat(base.getProperty("spring.rabbitmq.host")).isEqualTo("${SPRING_RABBITMQ_HOST}");
        assertThat(base.getProperty("spring.rabbitmq.username")).isEqualTo("${SPRING_RABBITMQ_USERNAME}");
        assertThat(base.getProperty("spring.rabbitmq.password")).isEqualTo("${SPRING_RABBITMQ_PASSWORD}");
        assertThat(base.getProperty("repoguard.security.encryption-key"))
            .isEqualTo("${REPOGUARD_SECURITY_ENCRYPTION_KEY}");
        assertThat(base.getProperty("repoguard.security.encryption-key-id"))
            .isEqualTo("${REPOGUARD_SECURITY_ENCRYPTION_KEY_ID}");
        assertThat(base.getProperty("repoguard.auth.token-secret"))
            .isEqualTo("${REPOGUARD_AUTH_TOKEN_SECRET}");
        assertThat(base.getProperty("repoguard.auth.registration-enabled"))
            .isEqualTo("${REPOGUARD_AUTH_REGISTRATION_ENABLED:false}");
        assertThat(base.getProperty("app.cors.allowed-origins")).isEqualTo("${APP_CORS_ALLOWED_ORIGINS:}");
    }

    @Test
    void developmentAndTestProfilesOwnLocalOnlyDefaults() throws IOException {
        for (String resource : new String[] {"application-dev.yml", "application-test.yml"}) {
            PropertySource<?> profile = load(resource);

            assertThat(profile.getProperty("spring.rabbitmq.host")).isEqualTo("${SPRING_RABBITMQ_HOST:localhost}");
            assertThat(profile.getProperty("spring.rabbitmq.username"))
                .isEqualTo("${SPRING_RABBITMQ_USERNAME:repoguard}");
            assertThat(profile.getProperty("spring.rabbitmq.password"))
                .isEqualTo("${SPRING_RABBITMQ_PASSWORD:repoguard}");
            assertThat(profile.getProperty("repoguard.security.encryption-key"))
                .isEqualTo("${REPOGUARD_SECURITY_ENCRYPTION_KEY:repoguard-local-dev-encryption-key}");
            assertThat(profile.getProperty("repoguard.security.encryption-key-id"))
                .isEqualTo("${REPOGUARD_SECURITY_ENCRYPTION_KEY_ID:local}");
            assertThat(profile.getProperty("repoguard.auth.token-secret"))
                .isEqualTo("${REPOGUARD_AUTH_TOKEN_SECRET:changeme-local-dev}");
            assertThat(profile.getProperty("repoguard.auth.registration-enabled"))
                .isEqualTo("${REPOGUARD_AUTH_REGISTRATION_ENABLED:true}");
        }
    }

    @Test
    void productionProfileForcesSecureAuthenticationCookiesByDefault() throws IOException {
        PropertySource<?> production = load("application-prod.yml");

        assertThat(production.getProperty("repoguard.auth.secure-cookies"))
            .isEqualTo("${REPOGUARD_AUTH_SECURE_COOKIES:true}");
    }

    private PropertySource<?> load(String resource) throws IOException {
        return new YamlPropertySourceLoader()
            .load(resource, new ClassPathResource(resource))
            .get(0);
    }
}
