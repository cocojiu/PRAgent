package com.repoguard.agent.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GithubAppProperties.class)
public class GithubAppConfiguration {
}
