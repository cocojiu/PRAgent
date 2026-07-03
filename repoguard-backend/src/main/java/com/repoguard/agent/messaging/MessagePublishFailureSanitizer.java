package com.repoguard.agent.messaging;

import com.repoguard.agent.common.SensitiveTextSanitizer;

public final class MessagePublishFailureSanitizer {

    private MessagePublishFailureSanitizer() {
    }

    public static String sanitize(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage();
        return sanitizeText(message);
    }

    static String sanitizeText(String message) {
        return SensitiveTextSanitizer.sanitize(message);
    }
}
