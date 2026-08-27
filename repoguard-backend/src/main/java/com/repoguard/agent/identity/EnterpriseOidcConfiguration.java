package com.repoguard.agent.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnterpriseOidcProperties.class)
public class EnterpriseOidcConfiguration {
}
