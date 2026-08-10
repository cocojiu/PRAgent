package com.repoguard.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeRoleConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeRoleConfiguration.class);

    @Bean
    public RuntimeRoleContract runtimeRoleContract(Environment environment) {
        RuntimeRoleContract contract = RuntimeRoleContract.resolve(environment);
        if (contract.derivedFromLegacyFlags()) {
            LOGGER.warn(
                "Legacy REPOGUARD_API_ENABLED/REPOGUARD_WORKER_ENABLED flags resolved runtime role={}; "
                    + "migrate to REPOGUARD_RUNTIME_ROLE",
                contract.role().value()
            );
        }
        LOGGER.info(
            "Runtime role initialized role={} deploymentMode={} apiEnabled={} workerEnabled={} schedulerEnabled={} apiInstanceCount={} rateLimitStore={} authAccountCacheEnabled={}",
            contract.role().value(),
            contract.deploymentMode().value(),
            contract.apiEnabled(),
            contract.workerEnabled(),
            contract.schedulerEnabled(),
            contract.apiInstanceCount(),
            contract.rateLimitStore().value(),
            contract.authenticationAccountCacheEnabled()
        );
        return contract;
    }
}
