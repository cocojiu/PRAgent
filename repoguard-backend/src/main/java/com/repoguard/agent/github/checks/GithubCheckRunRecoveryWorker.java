package com.repoguard.agent.github.checks;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class GithubCheckRunRecoveryWorker {

    private final GithubCheckRunReconciler reconciler;

    public GithubCheckRunRecoveryWorker(GithubCheckRunReconciler reconciler) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    public void recover() {
        reconciler.reconcileDue();
    }
}
