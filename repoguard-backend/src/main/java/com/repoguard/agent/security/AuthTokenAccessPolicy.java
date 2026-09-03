package com.repoguard.agent.security;

import java.util.List;
import java.util.Locale;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

final class AuthTokenAccessPolicy {

    private static final PathPattern API_PATTERN = apiPattern();

    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
        new PublicEndpoint("POST", "/api/v1/auth/register", "User registration"),
        new PublicEndpoint("POST", "/api/v1/auth/login", "User login"),
        new PublicEndpoint("POST", "/api/v1/auth/refresh", "Refresh token exchange"),
        new PublicEndpoint("POST", "/api/v1/auth/refresh-token/reset", "Refresh token reset"),
        new PublicEndpoint("POST", "/api/v1/auth/logout", "Refresh token logout"),
        new PublicEndpoint("POST", "/api/v1/github/webhooks", "GitHub webhook ingress"),
        new PublicEndpoint("POST", "/api/v1/scanners/sarif/ci/tasks/{taskId}/upload", "Short-lived CI SARIF upload")
    );

    private AuthTokenAccessPolicy() {
    }

    static boolean requiresAuth(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        PathContainer parsedPath = parsePath(path);
        if (!API_PATTERN.matches(parsedPath)) {
            return false;
        }
        return PUBLIC_ENDPOINTS.stream()
            .noneMatch(endpoint -> endpoint.matches(normalizedMethod, parsedPath));
    }

    static List<PublicEndpoint> publicEndpoints() {
        return PUBLIC_ENDPOINTS;
    }

    private static PathPattern apiPattern() {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        return parser.parse("/api/v1/**");
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }

    private static PathContainer parsePath(String path) {
        if (path == null || path.isBlank()) {
            return PathContainer.parsePath("");
        }
        int queryIndex = path.indexOf('?');
        return PathContainer.parsePath(queryIndex >= 0 ? path.substring(0, queryIndex) : path);
    }

    static final class PublicEndpoint {

        private final String method;
        private final String pathPattern;
        private final String description;
        private final PathPattern compiledPattern;

        private PublicEndpoint(String method, String pathPattern, String description) {
            this.method = method;
            this.pathPattern = pathPattern;
            this.description = description;
            this.compiledPattern = PathPatternParser.defaultInstance.parse(pathPattern);
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
            return method.equals(actualMethod) && compiledPattern.matches(actualPath);
        }
    }
}
