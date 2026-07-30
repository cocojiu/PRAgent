package com.repoguard.agent.worker;

import com.repoguard.agent.review.LlmStatus;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskFailureOutcomePolicy {

    String failedRiskLevel() {
        return "INFO";
    }

    String failedLlmStatus() {
        return LlmStatus.FAILED.code();
    }
}
