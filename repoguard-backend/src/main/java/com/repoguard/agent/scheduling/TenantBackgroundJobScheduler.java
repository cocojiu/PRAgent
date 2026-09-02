package com.repoguard.agent.scheduling;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.dashboard.DashboardDailySnapshotRecoveryWorker;
import com.repoguard.agent.github.comment.GithubCommentPublicationBatchRecoveryWorker;
import com.repoguard.agent.github.checks.GithubCheckRunRecoveryWorker;
import com.repoguard.agent.messaging.ReviewTaskPublishCompensator;
import com.repoguard.agent.notification.delivery.NotificationDeliveryRecoveryCompensator;
import com.repoguard.agent.notification.publish.NotificationEventPublishCompensator;
import com.repoguard.agent.retention.OperationalDataRetentionWorker;
import com.repoguard.agent.retention.ReviewAttemptRetentionWorker;
import com.repoguard.agent.review.quality.ReviewQualityBaselineRecoveryWorker;
import com.repoguard.agent.security.SecretReEncryptionJobWorker;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import com.repoguard.agent.worker.ReviewTaskRecoveryCompensator;
import com.repoguard.agent.service.ReviewWorkflowService;
import java.util.Optional;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class TenantBackgroundJobScheduler {

    private final TenantScheduledTaskRunner tenantRunner;
    private final DashboardDailySnapshotRecoveryWorker dashboardSnapshots;
    private final ReviewTaskRecoveryCompensator reviewRecovery;
    private final ReviewAttemptRetentionWorker reviewAttemptRetention;
    private final OperationalDataRetentionWorker operationalRetention;
    private final GithubCommentPublicationBatchRecoveryWorker githubCommentRecovery;
    private final NotificationEventPublishCompensator notificationPublish;
    private final NotificationDeliveryRecoveryCompensator notificationDelivery;
    private final ReviewTaskPublishCompensator reviewPublish;
    private final SecretReEncryptionJobWorker secretReEncryption;
    private final ReviewQualityBaselineRecoveryWorker qualityBaseline;
    private final GithubCheckRunRecoveryWorker githubCheckRunRecovery;
    private final ReviewWorkflowService reviewWorkflow;

    @Autowired
    public TenantBackgroundJobScheduler(
        TenantScheduledTaskRunner tenantRunner,
        DashboardDailySnapshotRecoveryWorker dashboardSnapshots,
        ReviewTaskRecoveryCompensator reviewRecovery,
        ReviewAttemptRetentionWorker reviewAttemptRetention,
        OperationalDataRetentionWorker operationalRetention,
        GithubCommentPublicationBatchRecoveryWorker githubCommentRecovery,
        NotificationEventPublishCompensator notificationPublish,
        NotificationDeliveryRecoveryCompensator notificationDelivery,
        ReviewTaskPublishCompensator reviewPublish,
        SecretReEncryptionJobWorker secretReEncryption,
        ReviewQualityBaselineRecoveryWorker qualityBaseline,
        GithubCheckRunRecoveryWorker githubCheckRunRecovery,
        Optional<ReviewWorkflowService> reviewWorkflow
    ) {
        this.tenantRunner = Objects.requireNonNull(tenantRunner, "tenantRunner");
        this.dashboardSnapshots = Objects.requireNonNull(dashboardSnapshots, "dashboardSnapshots");
        this.reviewRecovery = Objects.requireNonNull(reviewRecovery, "reviewRecovery");
        this.reviewAttemptRetention = Objects.requireNonNull(reviewAttemptRetention, "reviewAttemptRetention");
        this.operationalRetention = Objects.requireNonNull(operationalRetention, "operationalRetention");
        this.githubCommentRecovery = Objects.requireNonNull(githubCommentRecovery, "githubCommentRecovery");
        this.notificationPublish = Objects.requireNonNull(notificationPublish, "notificationPublish");
        this.notificationDelivery = Objects.requireNonNull(notificationDelivery, "notificationDelivery");
        this.reviewPublish = Objects.requireNonNull(reviewPublish, "reviewPublish");
        this.secretReEncryption = Objects.requireNonNull(secretReEncryption, "secretReEncryption");
        this.qualityBaseline = Objects.requireNonNull(qualityBaseline, "qualityBaseline");
        this.githubCheckRunRecovery = githubCheckRunRecovery;
        this.reviewWorkflow = reviewWorkflow.orElse(null);
    }

    public TenantBackgroundJobScheduler(
        TenantScheduledTaskRunner tenantRunner,
        DashboardDailySnapshotRecoveryWorker dashboardSnapshots,
        ReviewTaskRecoveryCompensator reviewRecovery,
        ReviewAttemptRetentionWorker reviewAttemptRetention,
        OperationalDataRetentionWorker operationalRetention,
        GithubCommentPublicationBatchRecoveryWorker githubCommentRecovery,
        NotificationEventPublishCompensator notificationPublish,
        NotificationDeliveryRecoveryCompensator notificationDelivery,
        ReviewTaskPublishCompensator reviewPublish,
        SecretReEncryptionJobWorker secretReEncryption,
        ReviewQualityBaselineRecoveryWorker qualityBaseline,
        GithubCheckRunRecoveryWorker githubCheckRunRecovery,
        ReviewWorkflowService reviewWorkflow
    ) {
        this(
            tenantRunner, dashboardSnapshots, reviewRecovery, reviewAttemptRetention, operationalRetention,
            githubCommentRecovery, notificationPublish, notificationDelivery, reviewPublish,
            secretReEncryption, qualityBaseline, githubCheckRunRecovery, Optional.ofNullable(reviewWorkflow)
        );
    }

    public TenantBackgroundJobScheduler(
        TenantScheduledTaskRunner tenantRunner,
        DashboardDailySnapshotRecoveryWorker dashboardSnapshots,
        ReviewTaskRecoveryCompensator reviewRecovery,
        ReviewAttemptRetentionWorker reviewAttemptRetention,
        OperationalDataRetentionWorker operationalRetention,
        GithubCommentPublicationBatchRecoveryWorker githubCommentRecovery,
        NotificationEventPublishCompensator notificationPublish,
        NotificationDeliveryRecoveryCompensator notificationDelivery,
        ReviewTaskPublishCompensator reviewPublish,
        SecretReEncryptionJobWorker secretReEncryption,
        ReviewQualityBaselineRecoveryWorker qualityBaseline
    ) {
        this(
            tenantRunner, dashboardSnapshots, reviewRecovery, reviewAttemptRetention, operationalRetention,
            githubCommentRecovery, notificationPublish, notificationDelivery, reviewPublish,
            secretReEncryption, qualityBaseline, null, Optional.empty()
        );
    }

    public TenantBackgroundJobScheduler(
        TenantScheduledTaskRunner tenantRunner,
        DashboardDailySnapshotRecoveryWorker dashboardSnapshots,
        ReviewTaskRecoveryCompensator reviewRecovery,
        ReviewAttemptRetentionWorker reviewAttemptRetention,
        OperationalDataRetentionWorker operationalRetention,
        GithubCommentPublicationBatchRecoveryWorker githubCommentRecovery,
        NotificationEventPublishCompensator notificationPublish,
        NotificationDeliveryRecoveryCompensator notificationDelivery,
        ReviewTaskPublishCompensator reviewPublish,
        SecretReEncryptionJobWorker secretReEncryption,
        ReviewQualityBaselineRecoveryWorker qualityBaseline,
        GithubCheckRunRecoveryWorker githubCheckRunRecovery
    ) {
        this(
            tenantRunner, dashboardSnapshots, reviewRecovery, reviewAttemptRetention, operationalRetention,
            githubCommentRecovery, notificationPublish, notificationDelivery, reviewPublish,
            secretReEncryption, qualityBaseline, githubCheckRunRecovery, Optional.empty()
        );
    }


    @Scheduled(fixedDelayString = "${repoguard.dashboard.snapshot-recovery-interval-ms:60000}")
    public void recoverDashboardSnapshots() {
        run("dashboard_snapshot_recovery", dashboardSnapshots::recoverDirtySnapshots);
    }

    @Scheduled(cron = "${repoguard.dashboard.snapshot-reconciliation-cron:0 15 3 * * *}")
    public void reconcileDashboardSnapshots() {
        run("dashboard_snapshot_reconciliation", dashboardSnapshots::reconcileCurrentWindows);
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.review-recovery-interval-ms:60000}")
    public void recoverReviewTasks() {
        run("review_execution_recovery", () -> {
            reviewRecovery.recoverStuckTasks();
            if (reviewWorkflow != null) {
                reviewWorkflow.escalateOverdue();
            }
        });
    }

    @Scheduled(cron = "${repoguard.operational-data-retention.review-attempt-cron:0 45 3 * * *}")
    public void retainReviewAttempts() {
        run("review_attempt_retention", reviewAttemptRetention::cleanup);
    }

    @Scheduled(cron = "${repoguard.operational-data-retention.cron:0 30 3 * * *}")
    public void retainOperationalData() {
        tenantRunner.runGlobal(
            "global_operational_data_retention",
            operationalRetention::cleanupGlobalData
        );
        run("tenant_operational_data_retention", operationalRetention::cleanupTenantData);
    }

    @Scheduled(fixedDelayString = "${app.github.comment-publish.recovery-interval-ms:60000}")
    public void recoverGithubCommentBatches() {
        run("github_comment_batch_recovery", githubCommentRecovery::recoverPublishBatches);
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.publish-compensation-interval-ms:60000}")
    public void compensateNotificationPublishing() {
        run("notification_publish_compensation", notificationPublish::compensate);
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.delivery-recovery-interval-ms:60000}")
    public void recoverNotificationDelivery() {
        run("notification_delivery_recovery", notificationDelivery::recoverExpiredClaims);
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.publish-compensation-interval-ms:60000}")
    public void compensateReviewPublishing() {
        run("review_publish_compensation", reviewPublish::compensatePublishFailures);
    }

    @Scheduled(fixedDelayString = "${repoguard.security.re-encryption.poll-interval-ms}")
    public void processSecretReEncryption() {
        run("secret_re_encryption", secretReEncryption::processDueJob);
    }

    @Scheduled(fixedDelayString = "${repoguard.review.quality-baseline-recovery-interval-ms:60000}")
    public void recoverQualityBaseline() {
        run("review_quality_baseline_recovery", qualityBaseline::recoverDirtySnapshot);
    }

    @Scheduled(fixedDelayString = "${app.github.check-run.recovery-interval-ms:5000}")
    public void recoverGithubCheckRuns() {
        if (githubCheckRunRecovery != null) {
            run("github_check_run_recovery", githubCheckRunRecovery::recover);
        }
    }

    @Scheduled(cron = "${repoguard.review.quality-baseline-reconciliation-cron:0 20 3 * * *}")
    public void reconcileQualityBaseline() {
        run("review_quality_baseline_reconciliation", qualityBaseline::reconcileSnapshot);
    }

    private void run(String operation, Runnable task) {
        tenantRunner.runForEachActiveTenant(operation, task);
    }
}
