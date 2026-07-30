package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

class GithubRestClientFactoryTest {

    @Test
    void requestFactoryAppliesGithubTimeouts() throws ReflectiveOperationException {
        Object requestFactory = GithubRestClientFactory.requestFactory();

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        HttpClient httpClient = (HttpClient) field(requestFactory, "httpClient");
        assertThat(httpClient.connectTimeout()).contains(GithubRestClientFactory.CONNECT_TIMEOUT);
        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(field(requestFactory, "readTimeout")).isEqualTo(GithubRestClientFactory.READ_TIMEOUT);
    }

    private Object field(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
