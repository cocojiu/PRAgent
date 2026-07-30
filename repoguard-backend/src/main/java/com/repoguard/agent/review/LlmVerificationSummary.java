package com.repoguard.agent.review;

record LlmVerificationSummary(int attempted, int verified, int rejected, int unavailable) {

    static LlmVerificationSummary empty() {
        return new LlmVerificationSummary(0, 0, 0, 0);
    }

    LlmVerificationSummary add(LlmVerificationSummary other) {
        if (other == null) {
            return this;
        }
        return new LlmVerificationSummary(
            attempted + other.attempted,
            verified + other.verified,
            rejected + other.rejected,
            unavailable + other.unavailable
        );
    }
}
