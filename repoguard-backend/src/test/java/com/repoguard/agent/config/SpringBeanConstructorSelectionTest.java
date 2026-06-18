package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubPullRequestClientImpl;
import com.repoguard.agent.messaging.RabbitReviewTaskPublisher;
import com.repoguard.agent.messaging.ReviewTaskPublishCompensator;
import com.repoguard.agent.review.LlmPullRequestReviewer;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.impl.DataRetentionServiceImpl;
import com.repoguard.agent.service.impl.GithubCommentApplicationServiceImpl;
import com.repoguard.agent.service.impl.MessageQueueHealthServiceImpl;
import com.repoguard.agent.service.impl.ReviewTaskCommandServiceImpl;
import com.repoguard.agent.service.impl.ReviewTaskQueryServiceImpl;
import com.repoguard.agent.service.impl.ReviewServiceImpl;
import com.repoguard.agent.worker.ReviewTaskExecutorImpl;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SpringBeanConstructorSelectionTest {

    @Test
    void springManagedBeansWithMultipleConstructorsDeclareSingleAutowiredConstructor() {
        List<Class<?>> springManagedTypes = List.of(
            GithubPullRequestClientImpl.class,
            RabbitReviewTaskPublisher.class,
            ReviewTaskPublishCompensator.class,
            LlmPullRequestReviewer.class,
            AuthTokenService.class,
            SecretCryptoService.class,
            DataRetentionServiceImpl.class,
            GithubCommentApplicationServiceImpl.class,
            MessageQueueHealthServiceImpl.class,
            ReviewTaskCommandServiceImpl.class,
            ReviewTaskQueryServiceImpl.class,
            ReviewServiceImpl.class,
            ReviewTaskExecutorImpl.class
        );

        for (Class<?> type : springManagedTypes) {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            long autowiredConstructors = Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
            assertThat(autowiredConstructors)
                .as(type.getName() + " must expose exactly one @Autowired constructor")
                .isEqualTo(1);
        }
    }
}
