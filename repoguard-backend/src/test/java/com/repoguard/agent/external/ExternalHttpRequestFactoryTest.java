package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

class ExternalHttpRequestFactoryTest {

    @Test
    void simpleAppliesConnectAndReadTimeoutsWithoutFollowingRedirects() throws ReflectiveOperationException {
        Object requestFactory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(8));

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(durationField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(8));
        HttpClient httpClient = (HttpClient) field(requestFactory, "httpClient");
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(5));
        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void sameTimeoutSecondsUsesDefaultAndMinimumTimeouts() throws ReflectiveOperationException {
        Object defaultFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(null, 60);
        Object minimumFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(0, 60);

        assertThat(durationField(defaultFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(60));
        assertThat(client(defaultFactory).connectTimeout()).contains(Duration.ofSeconds(60));
        assertThat(durationField(minimumFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(1));
        assertThat(client(minimumFactory).connectTimeout()).contains(Duration.ofSeconds(1));
    }

    @Test
    void factoriesWithSameConnectBudgetReuseOnePooledJdkClient() throws ReflectiveOperationException {
        Object first = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(8));
        Object second = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(20));

        assertThat(client(first)).isSameAs(client(second));
    }

    @Test
    void simpleRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> ExternalHttpRequestFactory.simple(Duration.ZERO, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectTimeout must be positive");
    }

    private HttpClient client(Object target) throws ReflectiveOperationException {
        return (HttpClient) field(target, "httpClient");
    }

    private Duration durationField(Object target, String fieldName) throws ReflectiveOperationException {
        return (Duration) field(target, fieldName);
    }

    private Object field(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        Field field = null;
        while (type != null && field == null) {
            try {
                field = type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return field.get(target);
    }
}
