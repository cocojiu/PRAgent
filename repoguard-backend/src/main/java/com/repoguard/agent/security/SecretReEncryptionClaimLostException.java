package com.repoguard.agent.security;

final class SecretReEncryptionClaimLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SecretReEncryptionClaimLostException(Long jobId) {
        super("Secret re-encryption job claim lost: " + jobId);
    }
}
