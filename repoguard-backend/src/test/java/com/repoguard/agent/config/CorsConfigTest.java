package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class CorsConfigTest {

    @Test
    void prodProfileRequiresAllowedOrigins() {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of(""));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CorsConfig(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.cors.allowed-origins");
    }

    @Test
    void nonProdProfileAllowsEmptyAllowedOrigins() {
        AppCorsProperties properties = new AppCorsProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "local");

        assertThatCode(() -> new CorsConfig(properties, environment)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWildcardOriginBecauseCredentialsAreAllowed() {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("*"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatThrownBy(() -> new CorsConfig(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot contain *");
    }

    @Test
    void ignoresBlankAllowedOriginsAndAllowsCredentials() {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of(" ", " http://localhost:5173 "));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        CorsConfig config = new CorsConfig(properties, environment);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        config.addCorsMappings(registry);

        CorsConfiguration corsConfiguration = registry.configurations().get("/api/**");
        assertThat(corsConfiguration.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(corsConfiguration.getExposedHeaders()).containsExactly("X-Trace-Id", "X-Error-Id");
        assertThat(corsConfiguration.getAllowCredentials()).isTrue();
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {

        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
