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
                "* /api/v1/config/**",
                "* /api/v1/enterprise/**",
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
                "POST /api/v1/reviews/{id}/findings/{findingId}/feedback",
                "POST /api/v1/review-workflow/escalations"
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
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/config/data-retention/cleanup-audits")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/observability/frontend/performance")).isFalse();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42")).isFalse();
    }

    @Test
    void matchesHandlerEquivalentPathVariants() {
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/manual;x=1")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("PUT", "/api/v1/%75sers/1/role")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/42;x=1/retry")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/%72eviews/manual")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/API/v1/users")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("get", "/api/v1/Config/review-policy")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1//users")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1//config//review-policy")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/config/review-policy?x=1")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42;x=1")).isFalse();
    }

    @Test
    void policyCoversEveryControllerAdminEndpoint() throws ClassNotFoundException {
        List<String> uncoveredEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (!requiresAdminOnlyRole(endpoint.controller(), endpoint.method())) {
                    continue;
                }
                if (!AdminApiKeyAccessPolicy.requiresAdminKey(endpoint.httpMethod(), concretePath(endpoint.path()))) {
                    uncoveredEndpoints.add(endpoint.httpMethod() + " " + endpoint.path());
                }
            }
        }

        assertThat(uncoveredEndpoints)
            .as("Admin API key policy must cover every ADMIN-only controller endpoint")
            .isEmpty();
    }

    @Test
    void policyContainsNoUnmappedOrNonAdminControllerScope() throws ClassNotFoundException {
        List<Endpoint> controllerEndpoints = new ArrayList<>();
        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            controllerEndpoints.addAll(ControllerEndpointCatalog.endpoints(controller));
        }

        List<String> unmappedPolicyEntries = AdminApiKeyAccessPolicy.protectedEndpoints().stream()
            .filter(policy -> controllerEndpoints.stream().noneMatch(endpoint -> policy.matches(
                endpoint.httpMethod(),
                concretePath(endpoint.path())
            )))
            .map(policy -> policy.method() + " " + policy.pathPattern())
            .toList();
        List<String> nonAdminMatches = controllerEndpoints.stream()
            .filter(endpoint -> !requiresAdminOnlyRole(endpoint.controller(), endpoint.method()))
            .filter(endpoint -> AdminApiKeyAccessPolicy.requiresAdminKey(
                endpoint.httpMethod(),
                concretePath(endpoint.path())
            ))
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path())
            .toList();

        assertThat(unmappedPolicyEntries)
            .as("Every Admin API key policy entry must match at least one controller mapping")
            .isEmpty();
        assertThat(nonAdminMatches)
            .as("Admin API key policy must not grant an ADMIN identity to non-admin controller mappings")
            .isEmpty();
    }

    private boolean requiresAdminOnlyRole(Class<?> controller, Method method) {
        RequireRole methodRole = method.getAnnotation(RequireRole.class);
        if (methodRole != null) {
            return containsOnlyElevatedManagementRole(methodRole);
        }
        RequireRole controllerRole = controller.getAnnotation(RequireRole.class);
        return controllerRole != null && containsOnlyElevatedManagementRole(controllerRole);
    }

    private boolean containsOnlyElevatedManagementRole(RequireRole requireRole) {
        List<String> roles = List.of(requireRole.value());
        return !roles.isEmpty() && roles.stream().allMatch(this::isElevatedManagementRole);
    }

    private boolean isElevatedManagementRole(String role) {
        return List.of("ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "RULE_ADMIN")
            .stream()
            .anyMatch(roleName -> roleName.equalsIgnoreCase(role));
    }

    private String concretePath(String path) {
        return path.replaceAll("\\{[^/]+}", "42");
    }
}
