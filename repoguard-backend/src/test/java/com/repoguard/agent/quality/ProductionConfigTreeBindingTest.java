package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

class ProductionConfigTreeBindingTest {

    @TempDir
    Path secretDirectory;

    @Test
    void configTreeFilenamesBindWhenLegacyEnvironmentKeysAreAbsent() throws IOException {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("spring.datasource.password", "database-file-secret");
        secrets.put("repoguard.security.encryption-key", "encryption-file-secret");
        secrets.put("repoguard.security.encryption-salt", "salt-file-secret");
        secrets.put("repoguard.auth.token-secret", "token-file-secret");
        secrets.put("app.security.admin-api-key.key", "admin-file-secret");
        secrets.put("app.github.webhook.secret", "webhook-file-secret");
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            Files.writeString(
                secretDirectory.resolve(secret.getKey()),
                secret.getValue(),
                StandardCharsets.UTF_8
            );
        }

        String configTree = "configtree:"
            + secretDirectory.toAbsolutePath().toString().replace('\\', '/')
            + "/";
        StandardEnvironment isolatedEnvironment = new StandardEnvironment();
        // Production Compose deliberately omits the legacy inline secret keys. Keep
        // runner-level validation variables from shadowing configtree in this test.
        isolatedEnvironment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        isolatedEnvironment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EmptyConfiguration.class)
            .web(WebApplicationType.NONE)
            .environment(isolatedEnvironment)
            .properties(
                "spring.profiles.active=test",
                "spring.config.import=" + configTree,
                "spring.datasource.password=test-fallback-value",
                "repoguard.security.encryption-key=test-fallback-value",
                "repoguard.security.encryption-salt=test-fallback-value",
                "repoguard.auth.token-secret=test-fallback-value",
                "app.security.admin-api-key.key=test-fallback-value",
                "app.github.webhook.secret=test-fallback-value"
            )
            .run()) {
            secrets.forEach((property, expected) -> assertThat(context.getEnvironment().getProperty(property))
                .as("configtree property %s", property)
                .isEqualTo(expected));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
