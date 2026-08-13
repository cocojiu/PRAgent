package com.repoguard.agent.review.quality;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ReviewQualityBaselineRecoveryWorkerTest {

    @Test
    void refreshesDirtySnapshot() {
        ReviewQualityBaselineService baselineService = org.mockito.Mockito.mock(ReviewQualityBaselineService.class);
        when(baselineService.refreshIfDirty()).thenReturn(true);
        ReviewQualityBaselineRecoveryWorker worker = new ReviewQualityBaselineRecoveryWorker(baselineService);

        worker.recoverDirtySnapshot();

        verify(baselineService).refreshIfDirty();
    }

    @Test
    void reconcilesSnapshot() {
        ReviewQualityBaselineService baselineService = org.mockito.Mockito.mock(ReviewQualityBaselineService.class);
        ReviewQualityBaselineRecoveryWorker worker = new ReviewQualityBaselineRecoveryWorker(baselineService);

        worker.reconcileSnapshot();

        verify(baselineService).reconcile();
    }
}
