package com.repoguard.agent.github.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(GithubWebhookProperties.class)
public class GithubWebhookConfig {

    public GithubWebhookConfig(GithubWebhookProperties properties, Environment environment) {
        properties.validateForProfiles(environment.getActiveProfiles());
    }
}
