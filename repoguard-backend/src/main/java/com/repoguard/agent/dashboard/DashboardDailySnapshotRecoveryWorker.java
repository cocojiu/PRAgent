package com.repoguard.agent.dashboard;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class DashboardDailySnapshotRecoveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardDailySnapshotRecoveryWorker.class);
    private static final int RECOVERY_BATCH_SIZE = 64;

    private final DashboardDailySnapshotService snapshotService;

    public DashboardDailySnapshotRecoveryWorker(DashboardDailySnapshotService snapshotService) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
    }

    public void recoverDirtySnapshots() {
        try {
            int refreshed = snapshotService.refreshDirtySnapshots(RECOVERY_BATCH_SIZE);
            if (refreshed > 0) {
                LOGGER.info("Dashboard daily snapshot recovery completed refreshedDates={}", refreshed);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("Dashboard daily snapshot recovery failed; dirty dates remain retryable", ex);
        }
    }

    public void reconcileCurrentWindows() {
        try {
            snapshotService.refreshCurrentWindows();
        } catch (RuntimeException ex) {
            LOGGER.warn("Dashboard daily snapshot reconciliation failed", ex);
        }
    }
}
