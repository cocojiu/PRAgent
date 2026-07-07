package com.repoguard.agent.worker;

import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryTimelineLabelFormatter {

    String requeuePending() {
        return "Review execution timed out; requeue pending";
    }

    String recoveryQueued() {
        return "Review execution timeout recovered; message requeued";
    }

    String recoveryPublishFailed(String error) {
        return "Review execution recovery publish failed: " + error;
    }
}
