package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.security.AllowAnonymous;
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

    private static final Set<String> SENSITIVE_READ_ENDPOINTS = Set.of(
        "EnterpriseTenantController#get",
        "EnterpriseTenantController#list",
        "EnterpriseTenantQuotaController#get",
        "SystemConfigController#getGithubIntegration",
        "SystemConfigController#getGithubChecksSetup",
        "SystemConfigController#getMysqlIntegration",
        "SystemConfigController#getRabbitMqIntegration",
        "SystemConfigController#getReviewPolicy",
        "SystemConfigController#getSystemSettings",
        "SystemConfigController#getReviewRules",
        "SystemConfigController#getReviewRuleVersions",
        "SystemConfigController#getReviewStrategyPolicy",
        "SystemConfigController#getReviewStrategyVersions",
        "SystemConfigController#getSecretReEncryptionJob",
        "SystemConfigController#listSecretReEncryptionJobs",
        "SystemConfigController#listSecretReEncryptionJobItems",
        "ReviewCalibrationController#getReviewCalibrationQueue",
        "ReviewCalibrationController#getModelReleaseCenter",
        "ReviewCalibrationController#listModelReleaseRuntimeMetrics",
        "ReviewCalibrationController#inspectModelReleaseDrift",
        "ReviewCalibrationController#listModelReleaseAudits",
        "ReviewCalibrationController#verifyModelReleaseAudit",
        "ReviewCalibrationController#exportModelReleaseAudits",
        "ReviewCalibrationController#listEvaluationReports",
        "ReviewCalibrationController#getEvaluationReport",
        "ReviewCalibrationController#compareEvaluationReports",
        "ReviewCalibrationController#exportEvaluationReport",
        "ReviewCalibrationController#getEvaluationRun",
        "NotificationIntegrationController#listBindings",
        "NotificationIntegrationController#listEvents",
        "NotificationIntegrationController#listDeliveries",
        "DataRetentionController#listCleanupAudits",
        "MessageQueueHealthController#getHealth",
        "UserManagementController#listUsers",
        "UserManagementController#listOperationAudits",
        "ScmProviderController#providers",
        "ScmProviderController#changeRequests",
        "ScmProviderController#diff",
        "ScmProviderController#head",
        "NotificationController#readKeys",
        "NotificationController#report",
        "ReviewWorkflowController#queue",
        "RepositoryPolicyController#preview",
        "RepositoryPolicyController#listSuppressions"
    );

    @Test
    void writeEndpointsRequireExplicitRoleUnlessAnonymous() throws ClassNotFoundException {
        List<String> unprotectedWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (!isWriteEndpoint(endpoint) || allowsAnonymous(endpoint)) {
                    continue;
                }
                if (!hasExplicitRole(endpoint.controller(), endpoint.method())) {
                    unprotectedWriteEndpoints.add(endpointId(endpoint) + " " + mappedEndpoint(endpoint));
                }
            }
        }

        assertThat(unprotectedWriteEndpoints)
            .as("Every non-anonymous POST/PUT/PATCH/DELETE endpoint must declare @RequireRole on the method or controller")
            .isEmpty();
    }

    @Test
    void anonymousEndpointsDoNotAlsoDeclareRoles() throws ClassNotFoundException {
        List<String> conflictingAnonymousEndpoints = new ArrayList<>();

        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (allowsAnonymous(endpoint) && hasExplicitRole(endpoint.controller(), endpoint.method())) {
                    conflictingAnonymousEndpoints.add(endpointId(endpoint) + " " + mappedEndpoint(endpoint));
                }
            }
        }

        assertThat(conflictingAnonymousEndpoints)
            .as("Anonymous controller mappings must not also declare a role requirement")
            .isEmpty();
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

    private boolean hasExplicitRole(Class<?> controller, Method method) {
        return method.isAnnotationPresent(RequireRole.class) || controller.isAnnotationPresent(RequireRole.class);
    }

    private boolean containsAdmin(RequireRole requireRole) {
        return List.of(requireRole.value()).stream()
            .anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }

    private boolean allowsAnonymous(Endpoint endpoint) {
        return endpoint.method().isAnnotationPresent(AllowAnonymous.class)
            || endpoint.controller().isAnnotationPresent(AllowAnonymous.class);
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
