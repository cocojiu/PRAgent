package com.repoguard.agent.security;

import java.util.Locale;

/**
 * Central role implication rules used by the HTTP authorization interceptor.
 * Legacy ADMIN/VIEWER values remain valid while new enterprise roles can be
 * introduced without duplicating permission checks in every controller.
 */
public final class RolePermissionPolicy {

    private RolePermissionPolicy() {
    }

    public static boolean allows(String actualRole, String requiredRole) {
        String actual = normalize(actualRole);
        String required = normalize(requiredRole);
        if (actual.isEmpty() || required.isEmpty()) {
            return false;
        }
        if ("ADMIN".equals(actual) || "PLATFORM_ADMIN".equals(actual)) {
            return true;
        }
        if (actual.equals(required)) {
            return true;
        }
        return switch (actual) {
            case "TENANT_ADMIN" -> "VIEWER".equals(required) || "READ_ONLY".equals(required);
            case "RULE_ADMIN" -> "VIEWER".equals(required) || "READ_ONLY".equals(required);
            case "REVIEWER" -> "VIEWER".equals(required) || "READ_ONLY".equals(required);
            case "VIEWER" -> "READ_ONLY".equals(required);
            case "READ_ONLY" -> "VIEWER".equals(required);
            default -> false;
        };
    }

    private static String normalize(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }
}
