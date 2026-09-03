package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.github.GithubAppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GithubCheckRunConfigurationTest {

    @Test
    void validatesThatChecksRequireAnEnabledGithubApp() {
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        checkProperties.setEnabled(true);
        GithubAppProperties appProperties = new GithubAppProperties();

        assertThatThrownBy(() -> new GithubCheckRunConfiguration(
            checkProperties, appProperties, new MockEnvironment().withProperty("spring.profiles.active", "prod")
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires repoguard.github-app.enabled");
    }

    @Test
    void acceptsEnabledChecksWhenGithubAppIsEnabled() {
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        checkProperties.setEnabled(true);
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);

        assertThatCode(() -> new GithubCheckRunConfiguration(
            checkProperties, appProperties, new MockEnvironment().withProperty("spring.profiles.active", "prod")
        )).doesNotThrowAnyException();
    }
}
