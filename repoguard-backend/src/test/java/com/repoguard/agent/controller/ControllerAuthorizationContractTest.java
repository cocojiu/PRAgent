package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.security.RequireRole;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ControllerAuthorizationContractTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
        AuthController.class,
        CacheStatsController.class,
        DashboardController.class,
        DataRetentionController.class,
        GithubWebhookController.class,
        MessageQueueHealthController.class,
        NotificationController.class,
        NotificationIntegrationController.class,
        ReviewController.class,
        SystemConfigController.class,
        UserManagementController.class
    );

    private static final Set<String> PUBLIC_WRITE_ENDPOINTS = Set.of(
        "AuthController#register",
        "AuthController#login",
        "AuthController#refresh",
        "AuthController#resetRefreshToken",
        "AuthController#logout",
        "GithubWebhookController#receive"
    );

    @Test
    void writeEndpointsRequireExplicitRoleUnlessPubliclyWhitelisted() {
        List<String> unprotectedWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteHandler(method) || isPublicWriteEndpoint(controller, method)) {
                    continue;
                }
                if (!hasRequireRole(controller, method)) {
                    unprotectedWriteEndpoints.add(endpointId(controller, method) + " " + writeMappings(method));
                }
            }
        }

        assertThat(unprotectedWriteEndpoints)
            .as("Every non-public POST/PUT/PATCH/DELETE endpoint must declare @RequireRole on the method or controller")
            .isEmpty();
    }

    @Test
    void publicWriteEndpointWhitelistStaysIntentional() {
        List<String> existingPublicWriteEndpoints = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isWriteHandler(method) && isPublicWriteEndpoint(controller, method)) {
                    existingPublicWriteEndpoints.add(endpointId(controller, method));
                }
            }
        }

        assertThat(existingPublicWriteEndpoints)
            .containsExactlyInAnyOrderElementsOf(PUBLIC_WRITE_ENDPOINTS);
    }

    private boolean isWriteHandler(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class);
    }

    private boolean hasRequireRole(Class<?> controller, Method method) {
        return controller.isAnnotationPresent(RequireRole.class)
            || method.isAnnotationPresent(RequireRole.class);
    }

    private boolean isPublicWriteEndpoint(Class<?> controller, Method method) {
        return PUBLIC_WRITE_ENDPOINTS.contains(endpointId(controller, method));
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

    private List<String> mappingPaths(String[] paths, String[] values, String httpMethod) {
        String[] selected = paths.length > 0 ? paths : values;
        if (selected.length == 0) {
            return List.of(httpMethod + " <empty>");
        }
        return List.of(selected).stream()
            .map(path -> httpMethod + " " + path)
            .toList();
    }

    @SuppressWarnings("unused")
    private boolean isReadHandler(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }
}
