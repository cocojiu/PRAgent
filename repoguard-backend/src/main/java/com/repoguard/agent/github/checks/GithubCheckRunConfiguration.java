package com.repoguard.agent.github.checks;

import com.repoguard.agent.github.GithubAppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(GithubCheckRunProperties.class)
public class GithubCheckRunConfiguration {

    public GithubCheckRunConfiguration(
        GithubCheckRunProperties properties,
        GithubAppProperties appProperties,
        Environment environment
    ) {
        properties.validateForProfiles(environment.getActiveProfiles());
        if (properties.isEnabled() && !appProperties.isEnabled()) {
            throw new IllegalStateException(
                "app.github.check-run.enabled requires repoguard.github-app.enabled because GitHub Checks write requires a GitHub App"
            );
        }
    }
}
