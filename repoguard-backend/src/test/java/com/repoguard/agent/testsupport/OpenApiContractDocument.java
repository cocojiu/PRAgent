package com.repoguard.agent.testsupport;

import com.repoguard.agent.testsupport.ControllerEndpointCatalog.Endpoint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OpenApiContractDocument {

    private static final String APPLICATION_JSON = "application/json";

    private OpenApiContractDocument() {
    }

    public static Map<String, Object> fromControllers(List<Class<?>> controllers) {
        List<Endpoint> endpoints = controllers.stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .sorted(OpenApiContractDocument::compareEndpoints)
            .toList();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("openapi", "3.1.0");
        document.put("info", info());
        document.put("paths", paths(endpoints));
        document.put("components", components(endpoints));
        return document;
    }

    public static List<String> operationLines(Map<String, Object> document) {
        Map<String, Object> paths = mapValue(document, "paths");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> operations = castMap(pathEntry.getValue());
            for (Map.Entry<String, Object> operationEntry : operations.entrySet()) {
                Map<String, Object> operation = castMap(operationEntry.getValue());
                lines.add(operationEntry.getKey().toUpperCase(Locale.ROOT) + " " + path
                    + " operationId=" + operation.get("x-controller-operation")
                    + " pathParams=" + parameterNames(operation, "path")
                    + " queryParams=" + parameterNames(operation, "query")
                    + " requestBody=" + requestBodyType(operation)
                    + " requestBodyRequired=" + requestBodyRequired(operation)
                    + " responseEnvelope=" + responseEnvelope(operation));
            }
        }
        return lines.stream().sorted().toList();
    }

    public static List<String> operationIds(Map<String, Object> document) {
        Map<String, Object> paths = mapValue(document, "paths");
        List<String> operationIds = new ArrayList<>();
        for (Object pathValue : paths.values()) {
            for (Object operationValue : castMap(pathValue).values()) {
                operationIds.add((String) castMap(operationValue).get("operationId"));
            }
        }
        return operationIds;
    }

    private static Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "RepoGuard Agent API");
        info.put("version", "0.0.1-SNAPSHOT");
        return info;
    }

    private static Map<String, Object> paths(List<Endpoint> endpoints) {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            Map<String, Object> pathItem = mapValue(paths, endpoint.path());
            pathItem.put(endpoint.httpMethod().toLowerCase(Locale.ROOT), operation(endpoint));
        }
        return paths;
    }

    private static Map<String, Object> operation(Endpoint endpoint) {
        Method method = endpoint.method();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("tags", List.of(tag(endpoint.controller())));
        operation.put("operationId", codegenOperationId(endpoint));
        operation.put("x-controller-operation", ControllerEndpointCatalog.endpointId(endpoint.controller(), method));
        operation.put("parameters", parameters(endpoint));
        if (ControllerEndpointCatalog.hasRequestBody(method)) {
            operation.put("requestBody", requestBody(method));
        }
        operation.put("responses", responses(method));
        return operation;
    }

    private static List<Map<String, Object>> parameters(Endpoint endpoint) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String name : ControllerEndpointCatalog.pathTemplateVariableNames(endpoint.path())) {
            parameters.add(parameter(name, "path", true));
        }
        for (String name : ControllerEndpointCatalog.requestParamNames(endpoint.method())) {
            parameters.add(parameter(name, "query", false));
        }
        return parameters;
    }

    private static Map<String, Object> parameter(String name, String in, boolean required) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("in", in);
        parameter.put("required", required);
        parameter.put("schema", scalarSchema("string"));
        return parameter;
    }

    private static Map<String, Object> requestBody(Method method) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        String requestBodyType = ControllerEndpointCatalog.requestBodyType(method);
        requestBody.put("required", ControllerEndpointCatalog.requestBodyRequired(method));
        requestBody.put("x-java-type", requestBodyType);
        requestBody.put("content", content(requestSchema(requestBodyType)));
        return requestBody;
    }

    private static Map<String, Object> requestSchema(String requestBodyType) {
        if ("byte[]".equals(requestBodyType)) {
            Map<String, Object> schema = scalarSchema("string");
            schema.put("format", "binary");
            return schema;
        }
        return refSchema(requestBodyType);
    }

    private static Map<String, Object> responses(Method method) {
        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, Object> ok = new LinkedHashMap<>();
        String responseEnvelope = method.getReturnType().getSimpleName();
        ok.put("description", "OK");
        ok.put("x-java-response-envelope", responseEnvelope);
        ok.put("content", content(refSchema(responseEnvelope)));
        responses.put("200", ok);
        return responses;
    }

    private static Map<String, Object> components(List<Endpoint> endpoints) {
        Set<String> schemaNames = new LinkedHashSet<>();
        schemaNames.add("ApiResponse");
        endpoints.stream()
            .map(endpoint -> ControllerEndpointCatalog.requestBodyType(endpoint.method()))
            .filter(type -> !"-".equals(type))
            .filter(type -> !"byte[]".equals(type))
            .forEach(schemaNames::add);

        Map<String, Object> schemas = new LinkedHashMap<>();
        for (String schemaName : schemaNames.stream().sorted().toList()) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("x-java-type", schemaName);
            schemas.put(schemaName, schema);
        }

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("schemas", schemas);
        return components;
    }

    private static Map<String, Object> content(Map<String, Object> schema) {
        Map<String, Object> mediaType = new LinkedHashMap<>();
        mediaType.put("schema", schema);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(APPLICATION_JSON, mediaType);
        return content;
    }

    private static Map<String, Object> refSchema(String schemaName) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$ref", "#/components/schemas/" + schemaName);
        return schema;
    }

    private static Map<String, Object> scalarSchema(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }

    private static String tag(Class<?> controller) {
        String name = controller.getSimpleName();
        return name.endsWith("Controller") ? name.substring(0, name.length() - "Controller".length()) : name;
    }

    private static String codegenOperationId(Endpoint endpoint) {
        return lowerFirst(endpoint.controller().getSimpleName()) + upperFirst(endpoint.method().getName());
    }

    private static String lowerFirst(String value) {
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }

    private static String upperFirst(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static int compareEndpoints(Endpoint left, Endpoint right) {
        int pathCompare = left.path().compareTo(right.path());
        if (pathCompare != 0) {
            return pathCompare;
        }
        return left.httpMethod().compareTo(right.httpMethod());
    }

    private static List<String> parameterNames(Map<String, Object> operation, String in) {
        return listValue(operation, "parameters").stream()
            .map(OpenApiContractDocument::castMap)
            .filter(parameter -> in.equals(parameter.get("in")))
            .map(parameter -> (String) parameter.get("name"))
            .toList();
    }

    private static String requestBodyType(Map<String, Object> operation) {
        if (!operation.containsKey("requestBody")) {
            return "-";
        }
        return (String) castMap(operation.get("requestBody")).get("x-java-type");
    }

    private static boolean requestBodyRequired(Map<String, Object> operation) {
        if (!operation.containsKey("requestBody")) {
            return false;
        }
        return (boolean) castMap(operation.get("requestBody")).get("required");
    }

    private static String responseEnvelope(Map<String, Object> operation) {
        Map<String, Object> responses = mapValue(operation, "responses");
        return (String) castMap(responses.get("200")).get("x-java-response-envelope");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value, "value");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listValue(Map<String, Object> source, String key) {
        return (List<Object>) Objects.requireNonNull(source.get(key), key);
    }
}
