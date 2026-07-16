package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class ExplicitProfileEnvironmentPostProcessorTest {

    private final ExplicitProfileEnvironmentPostProcessor postProcessor =
        new ExplicitProfileEnvironmentPostProcessor();

    @Test
    void rejectsEnvironmentWithoutAnActiveProfile() {
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("explicit Spring profile");
    }

    @Test
    void acceptsExplicitLocalProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "local");

        assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMixedLocalAndProductionLikeProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be combined");
    }

    @Test
    void isRegisteredAsABootEnvironmentPostProcessor() throws IOException {
        Properties factories = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(input).isNotNull();
            factories.load(input);
        }

        assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
            .contains(ExplicitProfileEnvironmentPostProcessor.class.getName());
    }
}
