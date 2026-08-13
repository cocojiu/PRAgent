package com.repoguard.agent.review.quality;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class ReviewQualityBaselineRecoveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewQualityBaselineRecoveryWorker.class);

    private final ReviewQualityBaselineService baselineService;

    public ReviewQualityBaselineRecoveryWorker(ReviewQualityBaselineService baselineService) {
        this.baselineService = Objects.requireNonNull(baselineService, "baselineService");
    }

    @Scheduled(fixedDelayString = "${repoguard.review.quality-baseline-recovery-interval-ms:60000}")
    public void recoverDirtySnapshot() {
        try {
            if (baselineService.refreshIfDirty()) {
                LOGGER.info("Review quality baseline dirty snapshot refreshed");
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("Review quality baseline recovery failed; dirty version remains retryable", ex);
        }
    }

    @Scheduled(cron = "${repoguard.review.quality-baseline-reconciliation-cron:0 20 3 * * *}")
    public void reconcileSnapshot() {
        try {
            baselineService.reconcile();
        } catch (RuntimeException ex) {
            LOGGER.warn("Review quality baseline reconciliation failed", ex);
        }
    }
}
