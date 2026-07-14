package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExternalHttpRequestFactoryTest {

    @Test
    void simpleAppliesConnectAndReadTimeouts() throws ReflectiveOperationException {
        Object requestFactory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(8));

        assertThat(intField(requestFactory, "connectTimeout")).isEqualTo(5_000);
        assertThat(intField(requestFactory, "readTimeout")).isEqualTo(8_000);
    }

    @Test
    void sameTimeoutSecondsUsesDefaultAndMinimumTimeouts() throws ReflectiveOperationException {
        Object defaultFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(null, 60);
        Object minimumFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(0, 60);

        assertThat(intField(defaultFactory, "connectTimeout")).isEqualTo(60_000);
        assertThat(intField(defaultFactory, "readTimeout")).isEqualTo(60_000);
        assertThat(intField(minimumFactory, "connectTimeout")).isEqualTo(1_000);
        assertThat(intField(minimumFactory, "readTimeout")).isEqualTo(1_000);
    }

    @Test
    void simpleRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> ExternalHttpRequestFactory.simple(Duration.ZERO, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectTimeout must be positive");
    }

    private int intField(Object target, String fieldName) throws ReflectiveOperationException {
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
        return field.getInt(target);
    }
}
