package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.security.RequireRole;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        "MessageQueueHealthController#getHealth",
        "UserManagementController#listUsers",
        "UserManagementController#listOperationAudits"
    );

    @Test
    void writeEndpointsRequireAdminRoleUnlessPubliclyWhitelisted() throws ClassNotFoundException {
        List<String> unprotectedWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : discoverControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteHandler(method) || isPublicWriteEndpoint(controller, method)) {
                    continue;
                }
                if (!hasAdminRole(controller, method)) {
                    unprotectedWriteEndpoints.add(endpointId(controller, method) + " " + writeMappings(method));
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

        for (Class<?> controller : discoverControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isWriteHandler(method) && isPublicWriteEndpoint(controller, method)) {
                    existingPublicWriteEndpoints.add(endpointId(controller, method));
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

        for (Class<?> controller : discoverControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isReadHandler(method)) {
                    continue;
                }
                if (hasAdminRole(controller, method)) {
                    adminReadEndpoints.add(endpointId(controller, method));
                }
                if (!isSensitiveReadEndpoint(controller, method)) {
                    continue;
                }
                existingSensitiveReadEndpoints.add(endpointId(controller, method));
                if (!hasAdminRole(controller, method)) {
                    unprotectedSensitiveReadEndpoints.add(endpointId(controller, method) + " " + readMappings(method));
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

    private List<Class<?>> discoverControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> controllerClassNames = scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE).stream()
            .map(BeanDefinition::getBeanClassName)
            .filter(Objects::nonNull)
            .sorted()
            .toList();

        List<Class<?>> controllers = new ArrayList<>();
        for (String controllerClassName : controllerClassNames) {
            controllers.add(Class.forName(controllerClassName));
        }
        controllers.sort(Comparator.comparing(Class::getName));
        return controllers;
    }

    private boolean isWriteHandler(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class);
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

    private boolean isPublicWriteEndpoint(Class<?> controller, Method method) {
        return PUBLIC_WRITE_ENDPOINTS.contains(endpointId(controller, method));
    }

    private boolean isSensitiveReadEndpoint(Class<?> controller, Method method) {
        return SENSITIVE_READ_ENDPOINTS.contains(endpointId(controller, method));
    }

    private String endpointId(Class<?> controller, Method method) {
        return controller.getSimpleName() + "#" + method.getName();
    }

    private List<String> writeMappings(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) {
            return mappingPaths(method.getAnnotation(PostMapping.class).path(), method.getAnnotation(PostMapping.class).value(), "POST");
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return mappingPaths(method.getAnnotation(PutMapping.class).path(), method.getAnnotation(PutMapping.class).value(), "PUT");
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return mappingPaths(method.getAnnotation(PatchMapping.class).path(), method.getAnnotation(PatchMapping.class).value(), "PATCH");
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return mappingPaths(method.getAnnotation(DeleteMapping.class).path(), method.getAnnotation(DeleteMapping.class).value(), "DELETE");
        }
        return List.of();
    }

    private List<String> readMappings(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return mappingPaths(method.getAnnotation(GetMapping.class).path(), method.getAnnotation(GetMapping.class).value(), "GET");
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            return mappingPaths(mapping.path(), mapping.value(), "REQUEST");
        }
        return List.of();
    }

    private List<String> mappingPaths(String[] paths, String[] values, String httpMethod) {
        String[] selected = paths.length > 0 ? paths : values;
        if (selected.length == 0) {
            return List.of(httpMethod + " <empty>");
        }
        return List.of(selected).stream()
            .map(path -> httpMethod + " " + path)
            .toList();
    }

    private boolean isReadHandler(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }
}
