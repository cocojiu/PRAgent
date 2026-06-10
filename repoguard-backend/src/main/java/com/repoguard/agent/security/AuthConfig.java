package com.repoguard.agent.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    public AuthConfig(AuthProperties authProperties, Environment environment) {
        authProperties.validateForProfiles(environment.getActiveProfiles());
    }
}
