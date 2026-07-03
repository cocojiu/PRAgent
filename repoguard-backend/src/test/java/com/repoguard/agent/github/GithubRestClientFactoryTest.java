package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class GithubRestClientFactoryTest {

    @Test
    void requestFactoryAppliesGithubTimeouts() throws ReflectiveOperationException {
        Object requestFactory = GithubRestClientFactory.requestFactory();

        assertThat(intField(requestFactory, "connectTimeout"))
            .isEqualTo((int) GithubRestClientFactory.CONNECT_TIMEOUT.toMillis());
        assertThat(intField(requestFactory, "readTimeout"))
            .isEqualTo((int) GithubRestClientFactory.READ_TIMEOUT.toMillis());
    }

    private int intField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
