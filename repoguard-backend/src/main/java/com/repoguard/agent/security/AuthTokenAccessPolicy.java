package com.repoguard.agent.security;

import java.util.List;
import java.util.Locale;

final class AuthTokenAccessPolicy {

    private static final String ANY_METHOD = "*";
    private static final String API_PREFIX = "/api/v1";

    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
        new PublicEndpoint(ANY_METHOD, "/api/v1/auth/register", "User registration"),
        new PublicEndpoint(ANY_METHOD, "/api/v1/auth/login", "User login"),
        new PublicEndpoint(ANY_METHOD, "/api/v1/auth/refresh", "Refresh token exchange"),
        new PublicEndpoint(ANY_METHOD, "/api/v1/auth/refresh-token/reset", "Refresh token reset"),
        new PublicEndpoint(ANY_METHOD, "/api/v1/auth/logout", "Refresh token logout"),
        new PublicEndpoint(ANY_METHOD, "/api/v1/github/webhooks", "GitHub webhook ingress")
    );

    private AuthTokenAccessPolicy() {
    }

    static boolean requiresAuth(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(path);
        if (!isApiPath(normalizedPath)) {
            return false;
        }
        return PUBLIC_ENDPOINTS.stream()
            .noneMatch(endpoint -> endpoint.matches(normalizedMethod, normalizedPath));
    }

    static List<PublicEndpoint> publicEndpoints() {
        return PUBLIC_ENDPOINTS;
    }

    private static boolean isApiPath(String path) {
        return path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/");
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

    record PublicEndpoint(String method, String pathPattern, String description) {

        boolean matches(String actualMethod, String actualPath) {
            return (ANY_METHOD.equals(method) || method.equals(actualMethod))
                && pathPattern.equals(actualPath);
        }
    }
}
