package com.repoguard.agent.security;

enum SecretReEncryptionJobStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
