package com.repoguard.agent.dashboard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class DashboardDailySnapshotRecoveryWorkerTest {

    @Test
    void recoversBoundedDirtyDateBatch() {
        DashboardDailySnapshotService snapshotService = org.mockito.Mockito.mock(
            DashboardDailySnapshotService.class
        );
        when(snapshotService.refreshDirtySnapshots(64)).thenReturn(3);
        DashboardDailySnapshotRecoveryWorker worker = new DashboardDailySnapshotRecoveryWorker(snapshotService);

        worker.recoverDirtySnapshots();

        verify(snapshotService).refreshDirtySnapshots(64);
    }

    @Test
    void reconcilesCurrentWindows() {
        DashboardDailySnapshotService snapshotService = org.mockito.Mockito.mock(
            DashboardDailySnapshotService.class
        );
        DashboardDailySnapshotRecoveryWorker worker = new DashboardDailySnapshotRecoveryWorker(snapshotService);

        worker.reconcileCurrentWindows();

        verify(snapshotService).refreshCurrentWindows();
    }
}
