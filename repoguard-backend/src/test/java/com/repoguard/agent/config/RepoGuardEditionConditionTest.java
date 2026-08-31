package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RepoGuardEditionConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RepoGuardEditionConfiguration.class, EditionTestConfig.class);

    @Test
    void personalEditionDoesNotRegisterEnterpriseBean() {
        contextRunner.run(context -> {
            assertThat(context.getBean(RepoGuardEditionContract.class).personal()).isTrue();
            assertThat(context.containsBean("enterpriseOnlyBean")).isFalse();
        });
    }

    @Test
    void enterpriseEditionRegistersEnterpriseBean() {
        contextRunner
            .withPropertyValues("app.edition=enterprise-experimental")
            .run(context -> {
                assertThat(context.getBean(RepoGuardEditionContract.class).enterpriseEnabled()).isTrue();
                assertThat(context.getBean("enterpriseOnlyBean")).isEqualTo("enterprise");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class EditionTestConfig {

        @Bean
        @EnterpriseEditionEnabled
        String enterpriseOnlyBean() {
            return "enterprise";
        }
    }
}
