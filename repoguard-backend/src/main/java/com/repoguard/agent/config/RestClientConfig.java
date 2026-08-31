package com.repoguard.agent.config;

import com.repoguard.agent.observability.W3CTracePropagationInterceptor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
            .requestInterceptor(new W3CTracePropagationInterceptor());
    }
}
