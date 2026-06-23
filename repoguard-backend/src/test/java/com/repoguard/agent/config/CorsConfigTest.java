package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
}
