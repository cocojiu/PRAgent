package com.repoguard.agent.security;

import java.util.List;
import java.util.Locale;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

final class AdminApiKeyAccessPolicy {

    private static final String ANY_METHOD = "*";
    private static final PathPatternParser PATTERN_PARSER = caseInsensitiveParser();

    private static final List<ProtectedEndpoint> PROTECTED_ENDPOINTS = List.of(
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/config/**", "Configuration read and write APIs"),
        new ProtectedEndpoint(ANY_METHOD, "/api/v1/enterprise/**", "Enterprise tenant control plane"),
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
        new ProtectedEndpoint("POST", "/api/v1/reviews/{id}/findings/{findingId}/feedback", "Finding feedback"),
        new ProtectedEndpoint("POST", "/api/v1/review-workflow/escalations", "Human review SLA escalation"),
        new ProtectedEndpoint(
            "POST",
            "/api/v1/scanners/sarif/ci/tasks/{taskId}/credentials",
            "CI SARIF credential minting"
        )
    );

    private AdminApiKeyAccessPolicy() {
    }

    static boolean requiresAdminKey(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        PathContainer parsedPath = parsePath(path);
        return PROTECTED_ENDPOINTS.stream()
            .anyMatch(endpoint -> endpoint.matches(normalizedMethod, parsedPath));
    }

    static List<ProtectedEndpoint> protectedEndpoints() {
        return PROTECTED_ENDPOINTS;
    }

    private static PathPatternParser caseInsensitiveParser() {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        return parser;
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }

    private static PathContainer parsePath(String path) {
        if (path == null || path.isBlank()) {
            return PathContainer.parsePath("");
        }
        int queryIndex = path.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? path.substring(0, queryIndex) : path;
        return PathContainer.parsePath(withoutQuery.replaceAll("/{2,}", "/"));
    }

    static final class ProtectedEndpoint {

        private final String method;
        private final String pathPattern;
        private final String description;
        private final PathPattern compiledPattern;

        private ProtectedEndpoint(String method, String pathPattern, String description) {
            this.method = method;
            this.pathPattern = pathPattern;
            this.description = description;
            this.compiledPattern = PATTERN_PARSER.parse(pathPattern.replaceAll("\\{([^}/]+)}", "{$1:[0-9]+}"));
        }

        String method() {
            return method;
        }

        String pathPattern() {
            return pathPattern;
        }

        String description() {
            return description;
        }

        boolean matches(String actualMethod, String actualPath) {
            return matches(normalizeMethod(actualMethod), parsePath(actualPath));
        }

        private boolean matches(String actualMethod, PathContainer actualPath) {
            if (!ANY_METHOD.equals(method) && !method.equals(actualMethod)) {
                return false;
            }
            return compiledPattern.matches(actualPath);
        }
    }
}
