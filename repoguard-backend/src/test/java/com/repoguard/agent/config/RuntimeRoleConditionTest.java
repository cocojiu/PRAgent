package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RuntimeRoleConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RuntimeRoleTestConfig.class, RuntimeRoleConfiguration.class);

    @Test
    void combinedRoleIsEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context.containsBean("apiBean")).isTrue();
            assertThat(context.containsBean("workerBean")).isTrue();
            assertThat(context.containsBean("schedulerBean")).isTrue();
            assertThat(context.getBean(RuntimeRoleContract.class).role())
                .isEqualTo(RuntimeRoleContract.Mode.COMBINED);
        });
    }

    @Test
    void apiRoleExcludesWorkerAndSchedulerCapabilities() {
        contextRunner
            .withPropertyValues("app.runtime.role=api")
            .run(context -> {
                assertThat(context.containsBean("apiBean")).isTrue();
                assertThat(context.containsBean("workerBean")).isFalse();
                assertThat(context.containsBean("schedulerBean")).isFalse();
            });
    }

    @Test
    void workerRoleExcludesApiAndIncludesFencedSchedulers() {
        contextRunner
            .withPropertyValues("app.runtime.role=worker")
            .run(context -> {
                assertThat(context.containsBean("apiBean")).isFalse();
                assertThat(context.containsBean("workerBean")).isTrue();
                assertThat(context.containsBean("schedulerBean")).isTrue();
            });
    }

    @Test
    void legacyFlagsRemainCompatibleDuringMigration() {
        contextRunner
            .withPropertyValues(
                "REPOGUARD_API_ENABLED=false",
                "REPOGUARD_WORKER_ENABLED=true"
            )
            .run(context -> {
                RuntimeRoleContract contract = context.getBean(RuntimeRoleContract.class);
                assertThat(contract.role()).isEqualTo(RuntimeRoleContract.Mode.WORKER);
                assertThat(contract.derivedFromLegacyFlags()).isTrue();
                assertThat(context.containsBean("apiBean")).isFalse();
                assertThat(context.containsBean("workerBean")).isTrue();
                assertThat(context.containsBean("schedulerBean")).isTrue();
            });
    }

    @Test
    void environmentStyleRolePropertiesAreResolvedDirectly() {
        contextRunner
            .withPropertyValues(
                "REPOGUARD_RUNTIME_ROLE=api",
                "REPOGUARD_DEPLOYMENT_MODE=split",
                "REPOGUARD_API_INSTANCE_COUNT=1"
            )
            .run(context -> {
                RuntimeRoleContract contract = context.getBean(RuntimeRoleContract.class);
                assertThat(contract.role()).isEqualTo(RuntimeRoleContract.Mode.API);
                assertThat(contract.deploymentMode()).isEqualTo(RuntimeRoleContract.DeploymentMode.SPLIT);
                assertThat(contract.rateLimitStore()).isEqualTo(RuntimeRoleContract.RateLimitStore.LOCAL);
                assertThat(context.containsBean("apiBean")).isTrue();
                assertThat(context.containsBean("workerBean")).isFalse();
            });
    }

    @Test
    void invalidOrConflictingRoleConfigurationFailsStartup() {
        assertStartupFailureContains(
            "app.runtime.role must be one of api, worker, or combined",
            "app.runtime.role=invalid"
        );
        assertStartupFailureContains(
            "conflicts with legacy API/Worker flags",
            "app.runtime.role=api",
            "app.runtime.api.enabled=true",
            "app.runtime.worker.enabled=true"
        );
        assertStartupFailureContains(
            "Runtime role cannot disable both API and Worker",
            "app.runtime.api.enabled=false",
            "app.runtime.worker.enabled=false"
        );
        assertStartupFailureContains(
            "app.runtime.deployment-mode must be monolith or split",
            "app.runtime.deployment-mode=invalid"
        );
        assertStartupFailureContains(
            "Split deployment requires app.runtime.role=api or worker",
            "app.runtime.role=combined",
            "app.runtime.deployment-mode=split"
        );
        assertStartupFailureContains(
            "Monolith deployment requires app.runtime.role=combined",
            "app.runtime.role=worker",
            "app.runtime.deployment-mode=monolith"
        );
        assertStartupFailureContains(
            "app.security.rate-limit-store must be local or database",
            "app.security.rate-limit-store=redis"
        );
    }

    @Test
    void horizontalApiScaleOutRequiresSharedRateLimits() {
        assertStartupFailureContains(
            "app.runtime.api.instance-count>1 requires app.security.rate-limit-store=database",
            "app.runtime.role=api",
            "app.runtime.api.instance-count=2"
        );
        contextRunner
            .withPropertyValues(
                "app.runtime.role=api",
                "app.runtime.api.instance-count=2",
                "app.security.rate-limit-store=database"
            )
            .run(context -> {
                RuntimeRoleContract contract = context.getBean(RuntimeRoleContract.class);
                assertThat(contract.apiInstanceCount()).isEqualTo(2);
                assertThat(contract.rateLimitStore()).isEqualTo(RuntimeRoleContract.RateLimitStore.DATABASE);
                assertThat(context.containsBean("apiBean")).isTrue();
            });
        assertStartupFailureContains(
            "API runtime requires app.runtime.api.instance-count to be positive",
            "app.runtime.role=api",
            "app.runtime.api.instance-count=0"
        );
        assertStartupFailureContains(
            "Worker runtime requires app.runtime.api.instance-count=0",
            "app.runtime.role=worker",
            "app.runtime.api.instance-count=1"
        );
    }

    private void assertStartupFailureContains(String message, String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).isNotNull();
            assertThat(rootCause(failure)).hasMessageContaining(message);
        });
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

        @Bean
        @SchedulerRuntimeEnabled
        Long schedulerBean() {
            return 1L;
        }
    }
}
