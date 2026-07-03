package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;

class RestClientConfigTest {

    @Test
    void restClientBuilderBeanIsPrototypeScoped() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RestClientConfig.class)) {
            RestClient.Builder first = context.getBean(RestClient.Builder.class);
            RestClient.Builder second = context.getBean(RestClient.Builder.class);

            assertThat(second).isNotSameAs(first);
        }
    }
}
