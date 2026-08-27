package com.repoguard.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dashboard.DashboardDailySnapshotRecoveryWorker;
import com.repoguard.agent.github.comment.GithubCommentPublicationBatchRecoveryWorker;
import com.repoguard.agent.messaging.ReviewTaskPublishCompensator;
import com.repoguard.agent.notification.delivery.NotificationDeliveryRecoveryCompensator;
import com.repoguard.agent.notification.publish.NotificationEventPublishCompensator;
import com.repoguard.agent.retention.OperationalDataRetentionWorker;
import com.repoguard.agent.retention.ReviewAttemptRetentionWorker;
import com.repoguard.agent.review.quality.ReviewQualityBaselineRecoveryWorker;
import com.repoguard.agent.security.SecretReEncryptionJobWorker;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import com.repoguard.agent.worker.ReviewTaskRecoveryCompensator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class TenantBackgroundJobSchedulerTest {

    private final TenantScheduledTaskRunner tenantRunner = mock(TenantScheduledTaskRunner.class);
    private final DashboardDailySnapshotRecoveryWorker dashboard = mock(DashboardDailySnapshotRecoveryWorker.class);
    private final ReviewTaskRecoveryCompensator reviewRecovery = mock(ReviewTaskRecoveryCompensator.class);
    private final ReviewAttemptRetentionWorker reviewAttemptRetention = mock(ReviewAttemptRetentionWorker.class);
    private final OperationalDataRetentionWorker operationalRetention = mock(OperationalDataRetentionWorker.class);
    private final GithubCommentPublicationBatchRecoveryWorker githubComments =
        mock(GithubCommentPublicationBatchRecoveryWorker.class);
    private final NotificationEventPublishCompensator notificationPublish =
        mock(NotificationEventPublishCompensator.class);
    private final NotificationDeliveryRecoveryCompensator notificationDelivery =
        mock(NotificationDeliveryRecoveryCompensator.class);
    private final ReviewTaskPublishCompensator reviewPublish = mock(ReviewTaskPublishCompensator.class);
    private final SecretReEncryptionJobWorker secretReEncryption = mock(SecretReEncryptionJobWorker.class);
    private final ReviewQualityBaselineRecoveryWorker qualityBaseline =
        mock(ReviewQualityBaselineRecoveryWorker.class);
    private final TenantBackgroundJobScheduler scheduler = new TenantBackgroundJobScheduler(
        tenantRunner,
        dashboard,
        reviewRecovery,
        reviewAttemptRetention,
        operationalRetention,
        githubComments,
        notificationPublish,
        notificationDelivery,
        reviewPublish,
        secretReEncryption,
        qualityBaseline
    );

    @Test
    void delegatesAllTwelveEntrypointsThroughTenantRunner() {
        when(tenantRunner.runForEachActiveTenant(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return new TenantScheduledTaskRunner.TenantRunSummary(1, 1, 0, 0);
        });
        when(tenantRunner.runGlobal(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });

        scheduler.recoverDashboardSnapshots();
        scheduler.reconcileDashboardSnapshots();
        scheduler.recoverReviewTasks();
        scheduler.retainReviewAttempts();
        scheduler.retainOperationalData();
        scheduler.recoverGithubCommentBatches();
        scheduler.compensateNotificationPublishing();
        scheduler.recoverNotificationDelivery();
        scheduler.compensateReviewPublishing();
        scheduler.processSecretReEncryption();
        scheduler.recoverQualityBaseline();
        scheduler.reconcileQualityBaseline();

        verify(tenantRunner, times(12)).runForEachActiveTenant(anyString(), any(Runnable.class));
        verify(tenantRunner).runGlobal(
            org.mockito.ArgumentMatchers.eq("global_operational_data_retention"),
            any(Runnable.class)
        );
        verify(dashboard).recoverDirtySnapshots();
        verify(dashboard).reconcileCurrentWindows();
        verify(reviewRecovery).recoverStuckTasks();
        verify(reviewAttemptRetention).cleanup();
        verify(operationalRetention).cleanupGlobalData();
        verify(operationalRetention).cleanupTenantData();
        verify(githubComments).recoverPublishBatches();
        verify(notificationPublish).compensate();
        verify(notificationDelivery).recoverExpiredClaims();
        verify(reviewPublish).compensatePublishFailures();
        verify(secretReEncryption).processDueJob();
        verify(qualityBaseline).recoverDirtySnapshot();
        verify(qualityBaseline).reconcileSnapshot();
    }

    @Test
    void ownsExactlyTwelveScheduledMethods() {
        long scheduledMethods = Stream.of(TenantBackgroundJobScheduler.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Scheduled.class))
            .count();

        assertThat(scheduledMethods).isEqualTo(12L);
    }

    @Test
    void scheduledAnnotationsExistOnlyOnTenantAwareEntrypoint() throws IOException {
        Path mainJava = Path.of("src/main/java");
        List<Path> sourcesWithSchedules;
        long annotationCount;
        try (Stream<Path> sources = Files.walk(mainJava)) {
            List<Path> javaSources = sources.filter(path -> path.toString().endsWith(".java")).toList();
            sourcesWithSchedules = javaSources.stream()
                .filter(this::containsScheduledAnnotation)
                .toList();
            annotationCount = javaSources.stream().mapToLong(this::scheduledAnnotationCount).sum();
        }

        assertThat(sourcesWithSchedules)
            .extracting(path -> path.getFileName().toString())
            .containsExactly("TenantBackgroundJobScheduler.java");
        assertThat(annotationCount).isEqualTo(12L);
    }

    private boolean containsScheduledAnnotation(Path path) {
        return scheduledAnnotationCount(path) > 0;
    }

    private long scheduledAnnotationCount(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return source.lines().filter(line -> line.trim().startsWith("@Scheduled(")).count();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect scheduled source " + path, exception);
        }
    }
}
