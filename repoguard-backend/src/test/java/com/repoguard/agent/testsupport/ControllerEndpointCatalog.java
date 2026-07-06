package com.repoguard.agent.testsupport;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

public final class ControllerEndpointCatalog {

    private ControllerEndpointCatalog() {
    }

    public static List<Class<?>> discoverControllers(String basePackage) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> controllerClassNames = scanner.findCandidateComponents(basePackage).stream()
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

    public static List<Endpoint> endpoints(Class<?> controller) {
        return List.of(controller.getDeclaredMethods()).stream()
            .filter(ControllerEndpointCatalog::isHandlerMethod)
            .flatMap(method -> endpoints(controller, method).stream())
            .toList();
    }

    public static List<Endpoint> endpoints(Class<?> controller, Method method) {
        List<String> basePaths = classMappingPaths(controller);
        List<MappingDefinition> methodMappings = methodMappings(method);
        List<Endpoint> endpoints = new ArrayList<>();
        for (String basePath : basePaths) {
            for (MappingDefinition mapping : methodMappings) {
                endpoints.add(new Endpoint(controller, method, mapping.httpMethod(), joinPaths(basePath, mapping.path())));
            }
        }
        return endpoints;
    }

    public static boolean isHandlerMethod(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
            || method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(PatchMapping.class);
    }

    public static List<String> classMappingPaths(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return List.of("");
        }
        List<String> paths = mappingPaths(mapping.path(), mapping.value());
        return paths.isEmpty() ? List.of("") : paths;
    }

    public static List<String> requestParamNames(Method method) {
        return List.of(method.getParameters()).stream()
            .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class))
            .map(ControllerEndpointCatalog::requestParamName)
            .toList();
    }

    public static String requestBodyType(Method method) {
        return List.of(method.getParameters()).stream()
            .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
            .map(parameter -> simpleTypeName(parameter.getType()))
            .findFirst()
            .orElse("-");
    }

    public static String endpointId(Class<?> controller, Method method) {
        return controller.getSimpleName() + "#" + method.getName();
    }

    private static List<MappingDefinition> methodMappings(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            return mappingDefinitions("GET", mapping.path(), mapping.value());
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            return mappingDefinitions("POST", mapping.path(), mapping.value());
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            PutMapping mapping = method.getAnnotation(PutMapping.class);
            return mappingDefinitions("PUT", mapping.path(), mapping.value());
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            DeleteMapping mapping = method.getAnnotation(DeleteMapping.class);
            return mappingDefinitions("DELETE", mapping.path(), mapping.value());
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            PatchMapping mapping = method.getAnnotation(PatchMapping.class);
            return mappingDefinitions("PATCH", mapping.path(), mapping.value());
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            String httpMethod = mapping.method().length == 1 ? mapping.method()[0].name() : "REQUEST";
            return mappingDefinitions(httpMethod, mapping.path(), mapping.value());
        }
        return List.of();
    }

    private static List<MappingDefinition> mappingDefinitions(String httpMethod, String[] paths, String[] values) {
        return mappingPaths(paths, values).stream()
            .map(path -> new MappingDefinition(httpMethod, path))
            .toList();
    }

    private static List<String> mappingPaths(String[] paths, String[] values) {
        String[] selectedPaths = paths.length > 0 ? paths : values;
        if (selectedPaths.length == 0) {
            return List.of("");
        }
        return List.of(selectedPaths);
    }

    private static String joinPaths(String basePath, String methodPath) {
        if (basePath.isBlank()) {
            return methodPath.isBlank() ? "/" : methodPath;
        }
        if (methodPath.isBlank()) {
            return basePath;
        }
        return basePath.endsWith("/")
            ? basePath.substring(0, basePath.length() - 1) + ensureLeadingSlash(methodPath)
            : basePath + ensureLeadingSlash(methodPath);
    }

    private static String ensureLeadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String requestParamName(Parameter parameter) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam == null) {
            return parameter.getName();
        }
        if (!requestParam.name().isBlank()) {
            return requestParam.name();
        }
        if (!requestParam.value().isBlank()) {
            return requestParam.value();
        }
        return parameter.getName();
    }

    private static String simpleTypeName(Class<?> type) {
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName();
    }

    private record MappingDefinition(String httpMethod, String path) {
    }

    public record Endpoint(Class<?> controller, Method method, String httpMethod, String path) {
    }
}
