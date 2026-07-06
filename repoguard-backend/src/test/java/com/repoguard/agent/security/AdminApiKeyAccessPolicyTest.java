package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.ControllerEndpointCatalog;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog.Endpoint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminApiKeyAccessPolicyTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";

    @Test
    void protectsIntentionalAdminApiKeyScope() {
        assertThat(AdminApiKeyAccessPolicy.protectedEndpoints())
            .extracting(endpoint -> endpoint.method() + " " + endpoint.pathPattern())
            .containsExactly(
                "* /api/v1/config",
                "* /api/v1/config/**",
                "* /api/v1/message-queue/**",
                "* /api/v1/notification-events",
                "* /api/v1/notification-events/**",
                "* /api/v1/notification-deliveries",
                "* /api/v1/users",
                "* /api/v1/users/**",
                "POST /api/v1/reviews/manual",
                "POST /api/v1/reviews/{id}/retry",
                "POST /api/v1/reviews/{id}/human-review",
                "POST /api/v1/reviews/{id}/github-comments",
                "POST /api/v1/reviews/{id}/findings/{findingId}/feedback"
            );
    }

    @Test
    void matchesProtectedTemplatePathsOnlyWhenNumericIdsArePresent() {
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/42/retry")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/42/findings/7/feedback")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/notification-events/9/retry")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/current/retry")).isFalse();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42/retry")).isFalse();
    }

    @Test
    void protectsConfigurationTreeForReadAndWriteAccess() {
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/config/review-policy")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("PUT", "/api/v1/config/system-settings")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/config/data-retention/cleanup")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42")).isFalse();
    }

    @Test
    void policyCoversEveryControllerAdminEndpoint() throws ClassNotFoundException {
        List<String> uncoveredEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (!requiresAdminRole(endpoint.controller(), endpoint.method())) {
                    continue;
                }
                if (!AdminApiKeyAccessPolicy.requiresAdminKey(endpoint.httpMethod(), concretePath(endpoint.path()))) {
                    uncoveredEndpoints.add(endpoint.httpMethod() + " " + endpoint.path());
                }
            }
        }

        assertThat(uncoveredEndpoints)
            .as("Admin API key policy must cover every @RequireRole(\"ADMIN\") controller endpoint")
            .isEmpty();
    }

    private boolean requiresAdminRole(Class<?> controller, Method method) {
        RequireRole methodRole = method.getAnnotation(RequireRole.class);
        if (methodRole != null) {
            return containsAdmin(methodRole);
        }
        RequireRole controllerRole = controller.getAnnotation(RequireRole.class);
        return controllerRole != null && containsAdmin(controllerRole);
    }

    private boolean containsAdmin(RequireRole requireRole) {
        return List.of(requireRole.value()).stream()
            .anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }

    private String concretePath(String path) {
        return path.replaceAll("\\{[^/]+}", "42");
    }
}
