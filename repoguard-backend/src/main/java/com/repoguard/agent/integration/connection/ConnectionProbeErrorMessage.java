package com.repoguard.agent.integration.connection;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import org.springframework.util.StringUtils;

final class ConnectionProbeErrorMessage {

    private static final int MAX_MESSAGE_LENGTH = 240;

    private ConnectionProbeErrorMessage() {
    }

    static String concise(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message) && ex.getCause() != null) {
            message = ex.getCause().getMessage();
        }
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String sanitized = SensitiveTextSanitizer.sanitize(message);
        return sanitized.length() > MAX_MESSAGE_LENGTH
            ? sanitized.substring(0, MAX_MESSAGE_LENGTH - 3) + "..."
            : sanitized;
    }
}
