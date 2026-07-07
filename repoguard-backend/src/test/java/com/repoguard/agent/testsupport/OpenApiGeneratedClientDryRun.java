package com.repoguard.agent.testsupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class OpenApiGeneratedClientDryRun {

    private OpenApiGeneratedClientDryRun() {
    }

    public static Map<String, GeneratedOperation> operations(Map<String, Object> document) {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, GeneratedOperation> operations = new LinkedHashMap<>();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            for (Map.Entry<String, Object> operationEntry : map(pathEntry.getValue()).entrySet()) {
                String method = operationEntry.getKey().toUpperCase(Locale.ROOT);
                Map<String, Object> operation = map(operationEntry.getValue());
                GeneratedOperation generatedOperation = new GeneratedOperation(
                    (String) operation.get("operationId"),
                    method,
                    path,
                    parameters(operation, "path"),
                    parameters(operation, "query"),
                    operation.containsKey("requestBody"),
                    requestBodyRequired(operation),
                    requestBodyType(operation),
                    responseDataType(operation)
                );
                operations.put(generatedOperation.endpointKey(), generatedOperation);
            }
        }
        return operations;
    }

    public static List<String> clientSurfaceLines(Map<String, Object> document) {
        return operations(document).values().stream()
            .map(GeneratedOperation::clientSurfaceLine)
            .sorted()
            .toList();
    }

    private static List<GeneratedParameter> parameters(Map<String, Object> operation, String location) {
        return list(operation.get("parameters")).stream()
            .map(OpenApiGeneratedClientDryRun::map)
            .filter(parameter -> location.equals(parameter.get("in")))
            .map(parameter -> new GeneratedParameter(
                (String) parameter.get("name"),
                Boolean.TRUE.equals(parameter.get("required")),
                typescriptScalarType(map(parameter.get("schema")))
            ))
            .toList();
    }

    private static boolean requestBodyRequired(Map<String, Object> operation) {
        if (!operation.containsKey("requestBody")) {
            return false;
        }
        Object required = map(operation.get("requestBody")).get("required");
        return required instanceof Boolean value && value;
    }

    private static String requestBodyType(Map<String, Object> operation) {
        if (!operation.containsKey("requestBody")) {
            return "-";
        }
        Object requestBodyType = map(operation.get("requestBody")).get("x-java-type");
        return requestBodyType instanceof String value
            ? FrontendApiContractCatalog.normalizeJavaResponseType(value)
            : "unknown";
    }

    private static String responseDataType(Map<String, Object> operation) {
        Map<String, Object> responses = map(operation.get("responses"));
        Object responseData = map(responses.get("200")).get("x-java-response-data");
        return responseData instanceof String value
            ? FrontendApiContractCatalog.normalizeJavaResponseType(value)
            : "unknown";
    }

    private static String typescriptScalarType(Map<String, Object> schema) {
        Object type = schema.get("type");
        if ("integer".equals(type) || "number".equals(type)) {
            return "number";
        }
        if ("boolean".equals(type)) {
            return "boolean";
        }
        if ("array".equals(type)) {
            return typescriptScalarType(map(schema.get("items"))) + "[]";
        }
        return "string";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> values ? (List<Object>) values : List.of();
    }

    public record GeneratedOperation(
        String operationId,
        String method,
        String path,
        List<GeneratedParameter> pathParameters,
        List<GeneratedParameter> queryParameters,
        boolean hasRequestBody,
        boolean requestBodyRequired,
        String requestBodyType,
        String responseDataType
    ) {

        public String endpointKey() {
            return method + " " + path;
        }

        public List<String> queryParamNames() {
            return queryParameters.stream().map(GeneratedParameter::name).toList();
        }

        public String clientSurfaceLine() {
            return operationId + "(" + inputShape() + "): Promise<" + responseDataType + ">; // " + endpointKey();
        }

        private String inputShape() {
            List<String> parts = new ArrayList<>();
            if (!pathParameters.isEmpty()) {
                parts.add("path: " + objectShape(pathParameters));
            }
            if (!queryParameters.isEmpty()) {
                parts.add("query?: " + objectShape(queryParameters));
            }
            if (hasRequestBody) {
                parts.add((requestBodyRequired ? "body: " : "body?: ") + requestBodyType);
            }
            if (parts.isEmpty()) {
                return "";
            }
            return "input" + (allInputPartsOptional() ? "?: " : ": ") + "{ " + String.join("; ", parts) + " }";
        }

        private boolean allInputPartsOptional() {
            return pathParameters.isEmpty() && (!hasRequestBody || !requestBodyRequired);
        }

        private String objectShape(List<GeneratedParameter> parameters) {
            return parameters.stream()
                .sorted(Comparator.comparing(GeneratedParameter::name))
                .map(parameter -> parameter.name() + (parameter.required() ? ": " : "?: ") + parameter.type())
                .collect(Collectors.joining("; ", "{ ", " }"));
        }
    }

    public record GeneratedParameter(String name, boolean required, String type) {
    }
}
