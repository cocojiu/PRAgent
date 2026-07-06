package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dashboard.DashboardLlmQualityTrendBuilder;
import com.repoguard.agent.dashboard.DashboardReviewTrendWindow;
import com.repoguard.agent.github.GithubPullRequestClientImpl;
import com.repoguard.agent.messaging.RabbitReviewTaskPublisher;
import com.repoguard.agent.messaging.ReviewTaskPublishCompensator;
import com.repoguard.agent.review.LlmPullRequestReviewer;
import com.repoguard.agent.review.RuleBasedPullRequestReviewer;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.impl.DataRetentionServiceImpl;
import com.repoguard.agent.service.impl.FindingFeedbackServiceImpl;
import com.repoguard.agent.service.impl.GithubCommentApplicationServiceImpl;
import com.repoguard.agent.service.impl.GithubCommentPreviewServiceImpl;
import com.repoguard.agent.service.impl.GithubCommentPreviewResponseAssembler;
import com.repoguard.agent.service.impl.GithubCommentPublishServiceImpl;
import com.repoguard.agent.service.impl.HumanReviewCommandService;
import com.repoguard.agent.service.impl.MessageQueueHealthServiceImpl;
import com.repoguard.agent.service.impl.ManualReviewCreationService;
import com.repoguard.agent.service.impl.ManualReviewIdempotencyCoordinator;
import com.repoguard.agent.service.impl.ReviewTaskAfterCommitPublisher;
import com.repoguard.agent.service.impl.ReviewTaskAfterCommitPublisherExecutor;
import com.repoguard.agent.service.impl.ReviewTaskCommandServiceImpl;
import com.repoguard.agent.service.impl.ReviewTaskDetailDataLoader;
import com.repoguard.agent.service.impl.ReviewTaskQueryServiceImpl;
import com.repoguard.agent.service.impl.ReviewTaskRetryService;
import com.repoguard.agent.service.impl.ReviewServiceImpl;
import com.repoguard.agent.worker.ReviewTaskExecutorImpl;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SpringBeanConstructorSelectionTest {

    @Test
    void springManagedBeansWithMultipleConstructorsDeclareSingleAutowiredConstructor() throws ClassNotFoundException {
        List<Class<?>> springManagedTypes = List.of(
            GithubPullRequestClientImpl.class,
            RabbitReviewTaskPublisher.class,
            ReviewTaskPublishCompensator.class,
            LlmPullRequestReviewer.class,
            RuleBasedPullRequestReviewer.class,
            AuthTokenService.class,
            SecretCryptoService.class,
            DataRetentionServiceImpl.class,
            FindingFeedbackServiceImpl.class,
            DashboardLlmQualityTrendBuilder.class,
            DashboardReviewTrendWindow.class,
            GithubCommentApplicationServiceImpl.class,
            GithubCommentPreviewServiceImpl.class,
            GithubCommentPreviewResponseAssembler.class,
            GithubCommentPublishServiceImpl.class,
            HumanReviewCommandService.class,
            MessageQueueHealthServiceImpl.class,
            ManualReviewCreationService.class,
            ManualReviewIdempotencyCoordinator.class,
            ReviewTaskAfterCommitPublisher.class,
            ReviewTaskAfterCommitPublisherExecutor.class,
            ReviewTaskCommandServiceImpl.class,
            ReviewTaskDetailDataLoader.class,
            ReviewTaskQueryServiceImpl.class,
            ReviewTaskRetryService.class,
            ReviewServiceImpl.class,
            ReviewTaskExecutorImpl.class
        );
        List<Class<?>> packagePrivateSpringManagedTypes = List.of(
            Class.forName("com.repoguard.agent.notification.DingTalkWebhookSigner"),
            Class.forName("com.repoguard.agent.notification.NotificationCounterNormalizer"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryCompletionService"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryEventStateUpdater"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryFailurePolicy"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryFailureClassifier"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryLogFactory"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryLogContextFormatter"),
            Class.forName("com.repoguard.agent.notification.NotificationDispatchRequestFactory"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryWorkerClock"),
            Class.forName("com.repoguard.agent.notification.NotificationDeliveryWorkerMetricsRecorder"),
            Class.forName("com.repoguard.agent.notification.NotificationEventKeyFactory"),
            Class.forName("com.repoguard.agent.notification.NotificationMessageJsonSerializer"),
            Class.forName("com.repoguard.agent.notification.NotificationProviderKeyNormalizer"),
            Class.forName("com.repoguard.agent.notification.NotificationPublishFailurePolicy"),
            Class.forName("com.repoguard.agent.notification.NotificationRetrySchedule"),
            Class.forName("com.repoguard.agent.notification.NotificationTextLimiter"),
            Class.forName("com.repoguard.agent.notification.WebhookNotificationContentBuilder"),
            Class.forName("com.repoguard.agent.notification.WebhookNotificationEventTextFormatter"),
            Class.forName("com.repoguard.agent.notification.WebhookNotificationFieldFormatter"),
            Class.forName("com.repoguard.agent.notification.WebhookNotificationResponseEvaluator"),
            Class.forName("com.repoguard.agent.review.LlmReviewPipeline"),
            Class.forName("com.repoguard.agent.service.impl.MessageQueueHealthQueryService"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionCacheInvalidator"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionClock"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionDiffStats"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionFailureClassifier"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionMetricsRecorder"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionTimelineRecorder"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionTransactionRunner"),
            Class.forName("com.repoguard.agent.worker.ReviewExecutionWorkflow"),
            Class.forName("com.repoguard.agent.worker.ReviewHumanReviewDecisionPolicy"),
            Class.forName("com.repoguard.agent.worker.ReviewTaskDurationPolicy"),
            Class.forName("com.repoguard.agent.worker.ReviewTaskFailureOutcomePolicy"),
            Class.forName("com.repoguard.agent.worker.ReviewTaskRecoveryTimelineRecorder")
        );

        for (Class<?> type : concat(springManagedTypes, packagePrivateSpringManagedTypes)) {
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

    private List<Class<?>> concat(List<Class<?>> first, List<Class<?>> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
