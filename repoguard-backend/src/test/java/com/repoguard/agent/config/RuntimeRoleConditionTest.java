package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RuntimeRoleConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RuntimeRoleTestConfig.class);

    @Test
    void runtimeRolesAreEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context.containsBean("apiBean")).isTrue();
            assertThat(context.containsBean("workerBean")).isTrue();
        });
    }

    @Test
    void apiRoleCanBeDisabledIndependently() {
        contextRunner
            .withPropertyValues("app.runtime.api.enabled=false")
            .run(context -> {
                assertThat(context.containsBean("apiBean")).isFalse();
                assertThat(context.containsBean("workerBean")).isTrue();
            });
    }

    @Test
    void workerRoleCanBeDisabledIndependently() {
        contextRunner
            .withPropertyValues("app.runtime.worker.enabled=false")
            .run(context -> {
                assertThat(context.containsBean("apiBean")).isTrue();
                assertThat(context.containsBean("workerBean")).isFalse();
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class RuntimeRoleTestConfig {

        @Bean
        @ApiRuntimeEnabled
        String apiBean() {
            return "api";
        }

        @Bean
        @WorkerRuntimeEnabled
        Integer workerBean() {
            return 1;
        }
    }
}
