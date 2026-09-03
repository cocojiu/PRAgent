package com.repoguard.agent.github.checks;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class GithubCheckRunRecoveryWorkerTest {

    @Test
    void delegatesRecoveryToReconciler() {
        GithubCheckRunReconciler reconciler = mock(GithubCheckRunReconciler.class);
        GithubCheckRunRecoveryWorker worker = new GithubCheckRunRecoveryWorker(reconciler);

        worker.recover();

        verify(reconciler).reconcileDue();
    }
}
