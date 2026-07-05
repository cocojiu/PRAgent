package com.repoguard.agent.security;

import java.util.List;
import java.util.Locale;

final class AdminApiKeyAccessPolicy {

    private static final String ANY_METHOD = "*";

    private static final List<ProtectedEndpoint> PROTECTED_ENDPOINTS = List.of(
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/config", "Configuration read and write APIs"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/config/**", "Configuration read and write APIs"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/message-queue/**", "Message queue operations"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/notification-events", "Notification event operations"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/notification-events/**", "Notification event operations"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/notification-deliveries", "Notification delivery operations"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/users", "User management operations"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/users/**", "User management operations"),
        new ProtectedEndpoint("POST", "/api/v1/reviews/manual", "Manual review creation"),
        new ProtectedEndpoint("POST", "/api/v1/reviews/{id}/retry", "Review retry"),
        new ProtectedEndpoint("POST", "/api/v1/reviews/{id}/human-review", "Human review decision"),
        new ProtectedEndpoint("POST", "/api/v1/reviews/{id}/github-comments", "GitHub comment publish"),
        new ProtectedEndpoint("POST", "/api/v1/reviews/{id}/findings/{findingId}/feedback", "Finding feedback")
    );

    private AdminApiKeyAccessPolicy() {
    }

    static boolean requiresAdminKey(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(path);
        return PROTECTED_ENDPOINTS.stream()
            .anyMatch(endpoint -> endpoint.matches(normalizedMethod, normalizedPath));
    }

    static List<ProtectedEndpoint> protectedEndpoints() {
        return PROTECTED_ENDPOINTS;
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }

    record ProtectedEndpoint(String method, String pathPattern, String description) {

        boolean matches(String actualMethod, String actualPath) {
            if (!ANY_METHOD.equals(method) && !method.equals(actualMethod)) {
                return false;
            }
            if (pathPattern.endsWith("/**")) {
                String prefix = pathPattern.substring(0, pathPattern.length() - 3);
                return actualPath.startsWith(prefix + "/");
            }
            String[] expectedParts = pathPattern.split("/");
            String[] actualParts = actualPath.split("/");
            if (expectedParts.length != actualParts.length) {
                return false;
            }
            for (int index = 0; index < expectedParts.length; index++) {
                String expectedPart = expectedParts[index];
                String actualPart = actualParts[index];
                if (isPathVariable(expectedPart)) {
                    if (!isPositiveNumeric(actualPart)) {
                        return false;
                    }
                    continue;
                }
                if (!expectedPart.equals(actualPart)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isPathVariable(String part) {
            return part.startsWith("{") && part.endsWith("}");
        }

        private boolean isPositiveNumeric(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            return true;
        }
    }
}
