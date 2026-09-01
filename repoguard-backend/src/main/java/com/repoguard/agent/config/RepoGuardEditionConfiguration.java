package com.repoguard.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RepoGuardEditionConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepoGuardEditionConfiguration.class);

    @Bean
    public RepoGuardEditionContract repoGuardEditionContract(Environment environment) {
        RepoGuardEditionContract contract = RepoGuardEditionContract.resolve(environment);
        LOGGER.info(
            "Product edition initialized edition={} enterpriseCapabilitiesEnabled={}",
            contract.edition().value(),
            contract.enterpriseEnabled()
        );
        return contract;
    }
}
