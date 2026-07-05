package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

        for (Class<?> controller : discoverControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isHandlerMethod(method) || !requiresAdminRole(controller, method)) {
                    continue;
                }
                for (MappedEndpoint endpoint : mappedEndpoints(controller, method)) {
                    if (!AdminApiKeyAccessPolicy.requiresAdminKey(endpoint.httpMethod(), concretePath(endpoint.path()))) {
                        uncoveredEndpoints.add(endpoint.httpMethod() + " " + endpoint.path());
                    }
                }
            }
        }

        assertThat(uncoveredEndpoints)
            .as("Admin API key policy must cover every @RequireRole(\"ADMIN\") controller endpoint")
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

    private List<MappedEndpoint> mappedEndpoints(Class<?> controller, Method method) {
        List<String> basePaths = classMappingPaths(controller);
        List<MappedEndpoint> methodMappings = methodMappings(method);
        return basePaths.stream()
            .flatMap(basePath -> methodMappings.stream()
                .map(endpoint -> new MappedEndpoint(endpoint.httpMethod(), combinePath(basePath, endpoint.path()))))
            .toList();
    }

    private List<String> classMappingPaths(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return List.of("");
        }
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length == 0 ? List.of("") : List.of(paths);
    }

    private List<MappedEndpoint> methodMappings(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return mappingPaths("GET", method.getAnnotation(GetMapping.class).path(), method.getAnnotation(GetMapping.class).value());
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return mappingPaths("POST", method.getAnnotation(PostMapping.class).path(), method.getAnnotation(PostMapping.class).value());
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return mappingPaths("PUT", method.getAnnotation(PutMapping.class).path(), method.getAnnotation(PutMapping.class).value());
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return mappingPaths("DELETE", method.getAnnotation(DeleteMapping.class).path(), method.getAnnotation(DeleteMapping.class).value());
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return mappingPaths("PATCH", method.getAnnotation(PatchMapping.class).path(), method.getAnnotation(PatchMapping.class).value());
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            return mappingPaths("*", mapping.path(), mapping.value());
        }
        return List.of();
    }

    private List<MappedEndpoint> mappingPaths(String httpMethod, String[] paths, String[] values) {
        String[] selected = paths.length > 0 ? paths : values;
        if (selected.length == 0) {
            return List.of(new MappedEndpoint(httpMethod, ""));
        }
        return List.of(selected).stream()
            .map(path -> new MappedEndpoint(httpMethod, path))
            .toList();
    }

    private boolean isHandlerMethod(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
            || method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(PatchMapping.class);
    }

    private String combinePath(String basePath, String methodPath) {
        if (basePath == null || basePath.isBlank()) {
            return normalizePath(methodPath);
        }
        if (methodPath == null || methodPath.isBlank()) {
            return normalizePath(basePath);
        }
        return normalizePath(basePath) + "/" + normalizePath(methodPath).replaceFirst("^/", "");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String concretePath(String path) {
        return path.replaceAll("\\{[^/]+}", "42");
    }

    private record MappedEndpoint(String httpMethod, String path) {
    }
}
