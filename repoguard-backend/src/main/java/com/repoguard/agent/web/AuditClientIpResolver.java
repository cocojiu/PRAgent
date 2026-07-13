package com.repoguard.agent.web;

import jakarta.servlet.http.HttpServletRequest;

public final class AuditClientIpResolver {

    private static final int MAX_CLIENT_IP_LENGTH = 64;

    private AuditClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Real-IP");
        return truncate(forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded, MAX_CLIENT_IP_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
