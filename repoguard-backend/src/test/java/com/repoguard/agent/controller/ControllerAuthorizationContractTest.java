package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog.Endpoint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControllerAuthorizationContractTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";

    private static final Set<String> PUBLIC_WRITE_ENDPOINTS = Set.of(
        "AuthController#register",
        "AuthController#login",
        "AuthController#refresh",
        "AuthController#resetRefreshToken",
        "AuthController#logout",
        "FrontendPerformanceController#recordPerformance",
        "GithubWebhookController#receive"
    );

    private static final Set<String> SENSITIVE_READ_ENDPOINTS = Set.of(
        "SystemConfigController#getGithubIntegration",
        "SystemConfigController#getMysqlIntegration",
        "SystemConfigController#getRabbitMqIntegration",
        "SystemConfigController#getReviewPolicy",
        "SystemConfigController#getSystemSettings",
        "SystemConfigController#getReviewRules",
        "NotificationIntegrationController#listBindings",
        "NotificationIntegrationController#listEvents",
        "NotificationIntegrationController#listDeliveries",
        "DataRetentionController#listCleanupAudits",
        "MessageQueueHealthController#getHealth",
        "UserManagementController#listUsers",
        "UserManagementController#listOperationAudits"
    );

    @Test
    void writeEndpointsRequireAdminRoleUnlessPubliclyWhitelisted() throws ClassNotFoundException {
        List<String> unprotectedWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (!isWriteEndpoint(endpoint) || isPublicWriteEndpoint(endpoint)) {
                    continue;
                }
                if (!hasAdminRole(endpoint.controller(), endpoint.method())) {
                    unprotectedWriteEndpoints.add(endpointId(endpoint) + " " + mappedEndpoint(endpoint));
                }
            }
        }

        assertThat(unprotectedWriteEndpoints)
            .as("Every non-public POST/PUT/PATCH/DELETE endpoint must declare @RequireRole(\"ADMIN\") on the method or controller")
            .isEmpty();
    }

    @Test
    void publicWriteEndpointWhitelistStaysIntentional() throws ClassNotFoundException {
        List<String> existingPublicWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (isWriteEndpoint(endpoint) && isPublicWriteEndpoint(endpoint)) {
                    existingPublicWriteEndpoints.add(endpointId(endpoint));
                }
            }
        }

        assertThat(existingPublicWriteEndpoints)
            .containsExactlyInAnyOrderElementsOf(PUBLIC_WRITE_ENDPOINTS);
    }

    @Test
    void sensitiveReadEndpointsRequireExplicitRole() throws ClassNotFoundException {
        List<String> unprotectedSensitiveReadEndpoints = new ArrayList<>();
        List<String> existingSensitiveReadEndpoints = new ArrayList<>();
        List<String> adminReadEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (!isReadEndpoint(endpoint)) {
                    continue;
                }
                if (hasAdminRole(endpoint.controller(), endpoint.method())) {
                    adminReadEndpoints.add(endpointId(endpoint));
                }
                if (!isSensitiveReadEndpoint(endpoint)) {
                    continue;
                }
                existingSensitiveReadEndpoints.add(endpointId(endpoint));
                if (!hasAdminRole(endpoint.controller(), endpoint.method())) {
                    unprotectedSensitiveReadEndpoints.add(endpointId(endpoint) + " " + mappedEndpoint(endpoint));
                }
            }
        }

        assertThat(existingSensitiveReadEndpoints)
            .containsExactlyInAnyOrderElementsOf(SENSITIVE_READ_ENDPOINTS);
        assertThat(adminReadEndpoints)
            .as("Every ADMIN read endpoint must stay in the sensitive read matrix")
            .containsExactlyInAnyOrderElementsOf(SENSITIVE_READ_ENDPOINTS);
        assertThat(unprotectedSensitiveReadEndpoints)
            .as("Sensitive GET endpoints must declare @RequireRole(\"ADMIN\") on the method or controller")
            .isEmpty();
    }

    private boolean isWriteEndpoint(Endpoint endpoint) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(endpoint.httpMethod());
    }

    private boolean hasAdminRole(Class<?> controller, Method method) {
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

    private boolean isPublicWriteEndpoint(Endpoint endpoint) {
        return PUBLIC_WRITE_ENDPOINTS.contains(endpointId(endpoint));
    }

    private boolean isSensitiveReadEndpoint(Endpoint endpoint) {
        return SENSITIVE_READ_ENDPOINTS.contains(endpointId(endpoint));
    }

    private String endpointId(Endpoint endpoint) {
        return ControllerEndpointCatalog.endpointId(endpoint.controller(), endpoint.method());
    }

    private String mappedEndpoint(Endpoint endpoint) {
        return endpoint.httpMethod() + " " + endpoint.path();
    }

    private boolean isReadEndpoint(Endpoint endpoint) {
        return "GET".equals(endpoint.httpMethod());
    }
}
