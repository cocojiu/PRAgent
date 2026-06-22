package com.repoguard.agent.github.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GithubWebhookProperties.class)
public class GithubWebhookConfig {
}
