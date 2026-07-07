package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.OpenApiContractDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OpenApiGeneratedClientContractTest {

    private static final Pattern FRONTEND_API_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): \\{\\R(?<body>.*?)^\\s{2}},?",
        Pattern.MULTILINE | Pattern.DOTALL
    );
    private static final Pattern FRONTEND_API_TYPE_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): ApiOperation<",
        Pattern.MULTILINE
    );
    private static final Pattern FRONTEND_METHOD_PATTERN = Pattern.compile("method: \"(GET|POST|PUT|DELETE)\"");
    private static final Pattern FRONTEND_LITERAL_PATH_PATTERN = Pattern.compile("path: \\(\\) => \"([^\"]+)\"");
    private static final Pattern FRONTEND_TEMPLATE_PATH_PATTERN = Pattern.compile("path: input => `([^`]+)`");
    private static final Pattern FRONTEND_INLINE_QUERY_PATTERN = Pattern.compile(
        "query: [^=]+=> \\(\\{(?<query>.*?)\\}\\)",
        Pattern.DOTALL
    );
    private static final Pattern FRONTEND_QUERY_KEY_PATTERN = Pattern.compile("^\\s+([a-zA-Z0-9_]+):", Pattern.MULTILINE);
    private static final Set<String> SERVER_ONLY_ENDPOINTS = Set.of(
        "POST /api/v1/auth/refresh",
        "POST /api/v1/github/webhooks"
    );

    @Test
    void reviewedOpenApiJsonCanDriveFrontendTypedClientCoverage() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();
        Set<String> frontendOrServerOnlyEndpoints = new java.util.LinkedHashSet<>();
        frontendEndpointContracts().values().stream()
            .map(FrontendEndpointContract::endpointKey)
            .forEach(frontendOrServerOnlyEndpoints::add);
        frontendOrServerOnlyEndpoints.addAll(SERVER_ONLY_ENDPOINTS);

        assertThat(generatedOperations.keySet())
            .as("Reviewed OpenAPI JSON operations must be covered by the frontend typed API contract or server-only allowlist")
            .allSatisfy(endpoint -> assertThat(frontendOrServerOnlyEndpoints).contains(endpoint));
    }

    @Test
    void frontendTypedClientStaysWithinReviewedOpenApiJson() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();

        assertThat(frontendEndpointContracts())
            .as("Frontend typed API contract must stay within reviewed OpenAPI JSON")
            .isNotEmpty()
            .allSatisfy((operation, frontendContract) -> {
                GeneratedOperation generatedOperation = generatedOperations.get(frontendContract.endpointKey());

                assertThat(generatedOperation)
                    .as(operation + " must exist in reviewed OpenAPI JSON")
                    .isNotNull();
                assertThat(generatedOperation.queryParamNames())
                    .as(operation + " query params must be declared by OpenAPI JSON")
                    .containsAll(frontendContract.queryParamNames());
                assertThat(frontendContract.hasRequestBody())
                    .as(operation + " request body usage must match OpenAPI JSON")
                    .isEqualTo(generatedOperation.hasRequestBody());
                assertThat(frontendContract.requestBodyRequired())
                    .as(operation + " request body required flag must match OpenAPI JSON")
                    .isEqualTo(generatedOperation.requestBodyRequired());
                assertThat(normalizeFrontendResponseType(frontendContract.responseType()))
                    .as(operation + " response type must match OpenAPI JSON response data type")
                    .isEqualTo(normalizeOpenApiResponseType(generatedOperation.responseDataType()));
            });
    }

    @Test
    void serverOnlyGeneratedOperationsStayExplicit() throws Exception {
        assertThat(generatedOperations().keySet())
            .as("Server-only generated operations must be explicit because no frontend client will exercise them")
            .containsAll(SERVER_ONLY_ENDPOINTS);
    }

    private Map<String, GeneratedOperation> generatedOperations() throws Exception {
        Map<String, Object> document = OpenApiContractDocument.fromJson(
            Files.readString(Path.of("src/test/resources/contracts/openapi.json"))
        );
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, GeneratedOperation> operations = new LinkedHashMap<>();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            for (Map.Entry<String, Object> operationEntry : map(pathEntry.getValue()).entrySet()) {
                String method = operationEntry.getKey().toUpperCase(Locale.ROOT);
                Map<String, Object> operation = map(operationEntry.getValue());
                String endpointKey = method + " " + path;
                operations.put(endpointKey, new GeneratedOperation(
                    method,
                    path,
                    queryParamNames(operation),
                    operation.containsKey("requestBody"),
                    requestBodyRequired(operation),
                    responseDataType(operation)
                ));
            }
        }
        return operations;
    }

    private List<String> queryParamNames(Map<String, Object> operation) {
        return list(operation.get("parameters")).stream()
            .map(this::map)
            .filter(parameter -> "query".equals(parameter.get("in")))
            .map(parameter -> (String) parameter.get("name"))
            .toList();
    }

    private boolean requestBodyRequired(Map<String, Object> operation) {
        if (!operation.containsKey("requestBody")) {
            return false;
        }
        Object required = map(operation.get("requestBody")).get("required");
        return required instanceof Boolean value && value;
    }

    private String responseDataType(Map<String, Object> operation) {
        Map<String, Object> responses = map(operation.get("responses"));
        Object responseData = map(responses.get("200")).get("x-java-response-data");
        return responseData instanceof String value ? value : "-";
    }

    private Map<String, FrontendEndpointContract> frontendEndpointContracts() throws Exception {
        String source = Files.readString(frontendContractsPath());
        Matcher operationMatcher = FRONTEND_API_OPERATION_PATTERN.matcher(source);
        Map<String, FrontendOperationTypes> operationTypes = frontendApiOperationTypes(source);
        Map<String, FrontendEndpointContract> contracts = new LinkedHashMap<>();
        while (operationMatcher.find()) {
            String operation = operationMatcher.group(1);
            String body = operationMatcher.group("body");
            FrontendOperationTypes types = operationTypes.getOrDefault(operation, new FrontendOperationTypes("", ""));
            extractFrontendPath(body).ifPresent(path -> contracts.put(
                operation,
                new FrontendEndpointContract(
                    frontendHttpMethod(body),
                    path,
                    frontendQueryParamNames(body),
                    body.contains("body:"),
                    body.contains("body:") && !frontendInputTypeAllowsUndefined(types.inputType()),
                    types.responseType()
                )
            ));
        }
        return contracts;
    }

    private Map<String, FrontendOperationTypes> frontendApiOperationTypes(String source) {
        Matcher matcher = FRONTEND_API_TYPE_OPERATION_PATTERN.matcher(source);
        Map<String, FrontendOperationTypes> operationTypes = new LinkedHashMap<>();
        while (matcher.find()) {
            List<String> arguments = apiOperationTypeArguments(source, matcher.end());
            operationTypes.put(matcher.group(1), new FrontendOperationTypes(arguments.get(0), arguments.get(1)));
        }
        return operationTypes;
    }

    private List<String> apiOperationTypeArguments(String source, int start) {
        int depth = 0;
        int split = -1;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                if (depth == 0) {
                    String input = source.substring(start, split).trim();
                    String response = source.substring(split + 1, index).trim();
                    return List.of(input, response);
                }
                depth--;
            } else if (current == ',' && depth == 0 && split < 0) {
                split = index;
            }
        }
        throw new IllegalStateException("Failed to parse frontend ApiOperation type arguments");
    }

    private boolean frontendInputTypeAllowsUndefined(String inputType) {
        return "undefined".equals(inputType) || inputType.contains("| undefined");
    }

    private Optional<String> extractFrontendPath(String operationBody) {
        Matcher literalPathMatcher = FRONTEND_LITERAL_PATH_PATTERN.matcher(operationBody);
        if (literalPathMatcher.find()) {
            return Optional.of(literalPathMatcher.group(1));
        }
        Matcher templatePathMatcher = FRONTEND_TEMPLATE_PATH_PATTERN.matcher(operationBody);
        if (templatePathMatcher.find()) {
            return Optional.of(normalizeFrontendTemplatePath(templatePathMatcher.group(1)));
        }
        return Optional.empty();
    }

    private String frontendHttpMethod(String operationBody) {
        Matcher methodMatcher = FRONTEND_METHOD_PATTERN.matcher(operationBody);
        return methodMatcher.find() ? methodMatcher.group(1) : "GET";
    }

    private List<String> frontendQueryParamNames(String operationBody) {
        if (!operationBody.contains("query:")) {
            return List.of();
        }
        if (operationBody.contains("query: notificationQuery")) {
            return List.of("page", "pageSize", "status", "taskId");
        }
        Matcher queryMatcher = FRONTEND_INLINE_QUERY_PATTERN.matcher(operationBody);
        if (!queryMatcher.find()) {
            return List.of("<unparsed-query>");
        }
        Matcher keyMatcher = FRONTEND_QUERY_KEY_PATTERN.matcher(queryMatcher.group("query"));
        List<String> queryParamNames = new ArrayList<>();
        while (keyMatcher.find()) {
            queryParamNames.add(keyMatcher.group(1));
        }
        return queryParamNames.stream().distinct().toList();
    }

    private String normalizeFrontendTemplatePath(String path) {
        return path
            .replace("${idSegment(input.findingId)}", "{findingId}")
            .replace("${idSegment(input.taskId)}", "{taskId}")
            .replace("${idSegment(input.id)}", "{id}");
    }

    private String normalizeOpenApiResponseType(String responseType) {
        return normalizeResponseType(responseType, true);
    }

    private String normalizeFrontendResponseType(String responseType) {
        return normalizeResponseType(responseType, false);
    }

    private String normalizeResponseType(String responseType, boolean openApi) {
        String type = responseType.replaceAll("\\s+", "");
        if (type.endsWith("[]")) {
            return normalizeResponseType(type.substring(0, type.length() - 2), openApi) + "[]";
        }
        if (type.startsWith("List<") && type.endsWith(">")) {
            return normalizeResponseType(genericContent(type), openApi) + "[]";
        }
        if (type.startsWith("Required<") && type.endsWith(">")) {
            return normalizeResponseType(genericContent(type), openApi);
        }
        if (type.startsWith("PageResponse<") && type.endsWith(">")) {
            return "PageResponse<" + normalizeResponseType(genericContent(type), openApi) + ">";
        }
        if (!openApi) {
            return type;
        }
        return openApiResponseTypeAliases().getOrDefault(type, defaultOpenApiResponseTypeName(type));
    }

    private String genericContent(String type) {
        return type.substring(type.indexOf('<') + 1, type.length() - 1);
    }

    private String defaultOpenApiResponseTypeName(String type) {
        return switch (type) {
            case "Void" -> "void";
            case "String" -> "string";
            default -> type.endsWith("Dto") ? type.substring(0, type.length() - "Dto".length()) : type;
        };
    }

    private Map<String, String> openApiResponseTypeAliases() {
        return Map.ofEntries(
            Map.entry("AuthCurrentUserDto", "CurrentUser"),
            Map.entry("CacheStatsResponse", "CacheStats"),
            Map.entry("DashboardLlmQualityResponse", "DashboardLlmQuality"),
            Map.entry("DashboardOverviewResponse", "DashboardOverview"),
            Map.entry("DashboardRulesResponse", "DashboardRules"),
            Map.entry("GithubCommentPreviewResponse", "GithubCommentPreview"),
            Map.entry("GithubCommentPublicationHistoryResponse", "GithubCommentPublicationHistory"),
            Map.entry("GithubCommentPublishResponse", "GithubCommentPublish"),
            Map.entry("GithubPullRequestOptionsResponse", "GithubPullRequestOptions"),
            Map.entry("MessageQueueHealthResponse", "MessageQueueHealth"),
            Map.entry("NotificationCenterDto", "NotificationCenter"),
            Map.entry("ReviewTaskListItem", "ReviewTask"),
            Map.entry("ReviewTaskStatusResponse", "ReviewTaskStatus"),
            Map.entry("ReviewTimelineItem", "TimelineItem"),
            Map.entry("ServiceIntegrationConfigDto", "ServiceIntegrationConfig"),
            Map.entry("SystemSettingsDto", "SystemSettings"),
            Map.entry("UserManagementItemDto", "ManagedUser"),
            Map.entry("UserOperationAuditDto", "UserOperationAudit")
        );
    }

    private Path frontendContractsPath() {
        Path candidate = Path.of("..", "repoguard-frontend", "src", "api", "contracts.ts");
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path rootCandidate = Path.of("repoguard-frontend", "src", "api", "contracts.ts");
        if (Files.exists(rootCandidate)) {
            return rootCandidate;
        }
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return value instanceof List<?> values ? (List<Object>) values : List.of();
    }

    record GeneratedOperation(
        String method,
        String path,
        List<String> queryParamNames,
        boolean hasRequestBody,
        boolean requestBodyRequired,
        String responseDataType
    ) {
    }

    record FrontendOperationTypes(String inputType, String responseType) {
    }

    record FrontendEndpointContract(
        String httpMethod,
        String path,
        List<String> queryParamNames,
        boolean hasRequestBody,
        boolean requestBodyRequired,
        String responseType
    ) {

        String endpointKey() {
            return httpMethod + " " + path;
        }
    }
}
