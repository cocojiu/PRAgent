package com.repoguard.agent.identity;

import com.repoguard.agent.config.EnterpriseEditionEnabled;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnterpriseEditionEnabled
@EnableConfigurationProperties(EnterpriseOidcProperties.class)
public class EnterpriseOidcConfiguration {
}
