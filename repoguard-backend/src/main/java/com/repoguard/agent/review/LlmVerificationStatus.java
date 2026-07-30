package com.repoguard.agent.review;

enum LlmVerificationStatus {
    NOT_REQUIRED,
    PENDING,
    VERIFIED,
    REJECTED,
    UNCERTAIN,
    UNAVAILABLE,
    PRECHECK_REJECTED,
    LIMIT_EXCEEDED
}
