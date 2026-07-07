package com.repoguard.agent.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog.Endpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestBody;

public final class OpenApiContractDocument {

    private static final String APPLICATION_JSON = "application/json";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS);

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
                    + " responseEnvelope=" + responseEnvelope(operation)
                    + " responseData=" + responseData(operation));
            }
        }
        return lines.stream().sorted().toList();
    }

    public static String responseDataTypeName(Method method) {
        return responseDataType(method).map(OpenApiContractDocument::typeName).orElse("-");
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

    public static String toJson(Map<String, Object> document) {
        try {
            return JSON.writeValueAsString(document) + System.lineSeparator();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OpenAPI contract document", ex);
        }
    }

    public static Map<String, Object> fromJson(String content) {
        try {
            return JSON.readValue(content, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse OpenAPI contract document", ex);
        }
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
        responseDataType(method).ifPresent(dataType -> ok.put("x-java-response-data", typeName(dataType)));
        ok.put("content", content(schemaForType(method.getGenericReturnType(), new LinkedHashSet<>())));
        responses.put("200", ok);
        return responses;
    }

    private static Map<String, Object> components(List<Endpoint> endpoints) {
        Set<Class<?>> schemaTypes = new LinkedHashSet<>();
        endpoints.stream()
            .map(endpoint -> requestBodyClass(endpoint.method()))
            .flatMap(Optional::stream)
            .filter(type -> type != byte[].class)
            .forEach(type -> collectSchemaTypes(type, schemaTypes));
        endpoints.stream()
            .map(endpoint -> endpoint.method().getGenericReturnType())
            .forEach(type -> collectSchemaTypes(type, schemaTypes));

        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("ApiResponse", apiResponseSchema(objectSchema("Object")));
        for (Class<?> schemaType : schemaTypes.stream().sorted(Comparator.comparing(Class::getSimpleName)).toList()) {
            schemas.put(schemaType.getSimpleName(), schemaForRecord(schemaType, schemaTypes));
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

    private static Optional<Class<?>> requestBodyClass(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return Optional.of(parameter.getType());
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> responseDataType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType parameterizedType
            && parameterizedType.getRawType() == ApiResponse.class) {
            return Optional.of(parameterizedType.getActualTypeArguments()[0]);
        }
        return Optional.empty();
    }

    private static void collectSchemaTypes(Type type, Set<Class<?>> schemaTypes) {
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass && isSchemaRecord(rawClass)) {
                collectSchemaTypes(rawClass, schemaTypes);
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                collectSchemaTypes(argument, schemaTypes);
            }
            return;
        }
        if (type instanceof Class<?> typeClass) {
            collectSchemaTypes(typeClass, schemaTypes);
        }
    }

    private static void collectSchemaTypes(Class<?> type, Set<Class<?>> schemaTypes) {
        if (!isSchemaRecord(type) || !schemaTypes.add(type)) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            Class<?> componentType = component.getType();
            if (isSchemaRecord(componentType)) {
                collectSchemaTypes(componentType, schemaTypes);
            }
            if (List.class.isAssignableFrom(componentType)) {
                listItemClass(component.getGenericType())
                    .filter(OpenApiContractDocument::isSchemaRecord)
                    .ifPresent(itemType -> collectSchemaTypes(itemType, schemaTypes));
            }
        }
    }

    private static Map<String, Object> schemaForRecord(Class<?> type, Set<Class<?>> schemaTypes) {
        if (!isSchemaRecord(type)) {
            return objectSchema(type.getSimpleName());
        }

        Map<String, Object> schema = objectSchema(type.getSimpleName());
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            Map<String, Object> property = schemaForComponent(component, schemaTypes);
            properties.put(component.getName(), property);
            if (isRequired(component)) {
                required.add(component.getName());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> objectSchema(String javaType) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("x-java-type", javaType);
        return schema;
    }

    private static Map<String, Object> schemaForComponent(RecordComponent component, Set<Class<?>> schemaTypes) {
        Map<String, Object> schema = schemaForType(component.getGenericType(), schemaTypes);
        applyValidationConstraints(component, schema);
        if (annotation(component, Valid.class).isPresent()) {
            schema.put("x-valid", true);
        }
        return schema;
    }

    private static Map<String, Object> schemaForType(Type type, Set<Class<?>> schemaTypes) {
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass) {
                if (List.class.isAssignableFrom(rawClass)) {
                    Map<String, Object> schema = scalarSchema("array");
                    schema.put("items", schemaForType(parameterizedType.getActualTypeArguments()[0], schemaTypes));
                    return schema;
                }
                if (rawClass == ApiResponse.class) {
                    return apiResponseSchema(schemaForType(parameterizedType.getActualTypeArguments()[0], schemaTypes));
                }
                if (isSchemaRecord(rawClass)) {
                    return schemaForParameterizedRecord(rawClass, parameterizedType, schemaTypes);
                }
            }
            return objectSchema(typeName(type));
        }
        if (type instanceof TypeVariable<?>) {
            return objectSchema(typeName(type));
        }
        if (!(type instanceof Class<?> typeClass)) {
            return objectSchema(typeName(type));
        }
        return schemaForClass(typeClass, type, schemaTypes);
    }

    private static Map<String, Object> schemaForClass(Class<?> type, Type genericType, Set<Class<?>> schemaTypes) {
        if (type == String.class) {
            return scalarSchema("string");
        }
        if (type == Boolean.class || type == boolean.class) {
            return scalarSchema("boolean");
        }
        if (type == Integer.class || type == int.class) {
            Map<String, Object> schema = scalarSchema("integer");
            schema.put("format", "int32");
            return schema;
        }
        if (type == Long.class || type == long.class) {
            Map<String, Object> schema = scalarSchema("integer");
            schema.put("format", "int64");
            return schema;
        }
        if (type == BigDecimal.class || type == Double.class || type == double.class || type == Float.class || type == float.class) {
            return scalarSchema("number");
        }
        if (type == OffsetDateTime.class || type == LocalDateTime.class) {
            Map<String, Object> schema = scalarSchema("string");
            schema.put("format", "date-time");
            return schema;
        }
        if (type == Void.class || type == void.class) {
            return scalarSchema("null");
        }
        if (List.class.isAssignableFrom(type)) {
            Map<String, Object> schema = scalarSchema("array");
            schema.put("items", listItemClass(genericType)
                .map(itemType -> schemaForType(itemType, schemaTypes))
                .orElseGet(() -> objectSchema("Object")));
            return schema;
        }
        if (type == ApiResponse.class) {
            return apiResponseSchema(objectSchema("Object"));
        }
        if (isSchemaRecord(type)) {
            collectSchemaTypes(type, schemaTypes);
            return refSchema(type.getSimpleName());
        }
        return objectSchema(type.getSimpleName());
    }

    private static Map<String, Object> apiResponseSchema(Map<String, Object> dataSchema) {
        Map<String, Object> schema = objectSchema("ApiResponse");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("success", scalarSchema("boolean"));
        properties.put("code", scalarSchema("string"));
        properties.put("message", scalarSchema("string"));
        properties.put("data", dataSchema);
        properties.put("timestamp", schemaForType(OffsetDateTime.class, new LinkedHashSet<>()));
        schema.put("properties", properties);
        schema.put("required", List.of("success", "code", "message", "timestamp"));
        return schema;
    }

    private static Map<String, Object> schemaForParameterizedRecord(
        Class<?> rawClass,
        ParameterizedType parameterizedType,
        Set<Class<?>> schemaTypes
    ) {
        collectSchemaTypes(rawClass, schemaTypes);
        Map<String, Type> typeArguments = typeArguments(rawClass, parameterizedType);
        Map<String, Object> schema = objectSchema(typeName(parameterizedType));
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : rawClass.getRecordComponents()) {
            Type resolvedType = resolveType(component.getGenericType(), typeArguments);
            Map<String, Object> property = schemaForType(resolvedType, schemaTypes);
            applyValidationConstraints(component, property);
            properties.put(component.getName(), property);
            if (isRequired(component)) {
                required.add(component.getName());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Type> typeArguments(Class<?> rawClass, ParameterizedType parameterizedType) {
        TypeVariable<?>[] variables = rawClass.getTypeParameters();
        Type[] arguments = parameterizedType.getActualTypeArguments();
        Map<String, Type> typeArguments = new LinkedHashMap<>();
        for (int index = 0; index < variables.length; index++) {
            typeArguments.put(variables[index].getName(), arguments[index]);
        }
        return typeArguments;
    }

    private static Type resolveType(Type type, Map<String, Type> typeArguments) {
        if (type instanceof TypeVariable<?> typeVariable) {
            return typeArguments.getOrDefault(typeVariable.getName(), type);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Type[] resolvedArguments = new Type[arguments.length];
            for (int index = 0; index < arguments.length; index++) {
                resolvedArguments[index] = resolveType(arguments[index], typeArguments);
            }
            return new ResolvedParameterizedType(parameterizedType.getRawType(), resolvedArguments, parameterizedType.getOwnerType());
        }
        return type;
    }

    private static String typeName(Type type) {
        if (type instanceof Class<?> typeClass) {
            return typeClass.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            String rawName = rawType instanceof Class<?> rawClass ? rawClass.getSimpleName() : rawType.getTypeName();
            return rawName + "<" + List.of(parameterizedType.getActualTypeArguments()).stream()
                .map(OpenApiContractDocument::typeName)
                .reduce((left, right) -> left + "," + right)
                .orElse("") + ">";
        }
        return type.getTypeName();
    }

    private record ResolvedParameterizedType(Type rawType, Type[] actualTypeArguments, Type ownerType)
        implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments;
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }
    }

    private static Optional<Class<?>> listItemClass(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return Optional.empty();
        }
        Type itemType = parameterizedType.getActualTypeArguments()[0];
        if (itemType instanceof Class<?> itemClass) {
            return Optional.of(itemClass);
        }
        return Optional.empty();
    }

    private static void applyValidationConstraints(RecordComponent component, Map<String, Object> schema) {
        annotation(component, NotBlank.class).ifPresent(ignored -> schema.put("minLength", 1));
        annotation(component, Email.class).ifPresent(ignored -> schema.put("format", "email"));
        annotation(component, Size.class).ifPresent(size -> {
            if ("array".equals(schema.get("type"))) {
                putIfNonDefault(schema, "minItems", size.min(), 0);
                putIfNonDefault(schema, "maxItems", size.max(), Integer.MAX_VALUE);
            } else {
                putIfNonDefault(schema, "minLength", size.min(), 0);
                putIfNonDefault(schema, "maxLength", size.max(), Integer.MAX_VALUE);
            }
        });
        annotation(component, Pattern.class).ifPresent(pattern -> schema.put("pattern", pattern.regexp()));
        annotation(component, Min.class).ifPresent(min -> schema.put("minimum", min.value()));
        annotation(component, Max.class).ifPresent(max -> schema.put("maximum", max.value()));
        annotation(component, DecimalMin.class).ifPresent(min -> {
            BigDecimal value = new BigDecimal(min.value());
            schema.put(min.inclusive() ? "minimum" : "exclusiveMinimum", value);
        });
        annotation(component, DecimalMax.class).ifPresent(max -> {
            BigDecimal value = new BigDecimal(max.value());
            schema.put(max.inclusive() ? "maximum" : "exclusiveMaximum", value);
        });
    }

    private static void putIfNonDefault(Map<String, Object> schema, String key, int value, int defaultValue) {
        if (value != defaultValue) {
            schema.put(key, value);
        }
    }

    private static boolean isRequired(RecordComponent component) {
        return component.getType().isPrimitive()
            || annotation(component, NotNull.class).isPresent()
            || annotation(component, NotBlank.class).isPresent();
    }

    private static boolean isSchemaRecord(Class<?> type) {
        return type.isRecord() && type.getName().startsWith("com.repoguard.agent.dto.");
    }

    private static <T extends Annotation> Optional<T> annotation(RecordComponent component, Class<T> annotationType) {
        T annotation = component.getAnnotation(annotationType);
        if (annotation != null) {
            return Optional.of(annotation);
        }
        annotation = component.getAccessor().getAnnotation(annotationType);
        return Optional.ofNullable(annotation);
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

    private static String responseData(Map<String, Object> operation) {
        Map<String, Object> responses = mapValue(operation, "responses");
        Object responseData = castMap(responses.get("200")).get("x-java-response-data");
        return responseData instanceof String value ? value : "-";
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
