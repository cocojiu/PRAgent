package com.repoguard.agent.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FrontendApiContractCatalog {

    private static final Pattern FRONTEND_API_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): \\{\\R(?<body>.*?)^\\s{2}},?",
        Pattern.MULTILINE | Pattern.DOTALL
    );
    private static final Pattern FRONTEND_API_TYPE_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): ApiOperation<",
        Pattern.MULTILINE
    );
    private static final Pattern FRONTEND_GENERATED_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): generatedEndpoint\\(\\s*\"([a-zA-Z0-9]+)\"",
        Pattern.MULTILINE
    );
    private static final Pattern FRONTEND_METHOD_PATTERN = Pattern.compile("method: \"(GET|POST|PUT|DELETE)\"");
    private static final Pattern FRONTEND_LITERAL_PATH_PATTERN = Pattern.compile("path: \\(\\) => \"([^\"]+)\"");
    private static final Pattern FRONTEND_TEMPLATE_PATH_PATTERN = Pattern.compile("path: input => `([^`]+)`");
    private static final Pattern FRONTEND_API_PATH_LITERAL_PATTERN = Pattern.compile("[\"`](/api/v1/[^\"`]+)[\"`]");
    private static final Pattern FRONTEND_GENERATED_ENDPOINT_PATTERN = Pattern.compile(
        "generatedEndpoint\\(\\s*\"([a-zA-Z0-9]+)\""
    );
    private static final Pattern FRONTEND_INLINE_QUERY_PATTERN = Pattern.compile(
        "query: [^=]+=> \\(\\{(?<query>.*?)\\}\\)",
        Pattern.DOTALL
    );
    private static final Pattern FRONTEND_QUERY_KEY_PATTERN = Pattern.compile("^\\s+([a-zA-Z0-9_]+):", Pattern.MULTILINE);

    private FrontendApiContractCatalog() {
    }

    public static Map<String, EndpointContract> endpointContracts() throws IOException {
        String source = Files.readString(contractsPath());
        Matcher operationMatcher = FRONTEND_API_OPERATION_PATTERN.matcher(source);
        Map<String, OperationTypes> operationTypes = apiOperationTypes(source);
        Map<String, OpenApiGeneratedClientDryRun.GeneratedOperation> generatedOperations =
            generatedOperationsById();
        Map<String, EndpointContract> contracts = new LinkedHashMap<>();
        while (operationMatcher.find()) {
            String operation = operationMatcher.group(1);
            String body = operationMatcher.group("body");
            OperationTypes types = operationTypes.getOrDefault(operation, new OperationTypes("", ""));
            Optional<String> generatedOperationId = generatedOperationId(body);
            if (generatedOperationId.isPresent()) {
                OpenApiGeneratedClientDryRun.GeneratedOperation generatedOperation =
                    generatedOperations.get(generatedOperationId.orElseThrow());
                if (generatedOperation == null) {
                    throw new IllegalStateException(
                        "Frontend generated endpoint is missing from reviewed OpenAPI JSON: "
                            + generatedOperationId.orElseThrow()
                    );
                }
                contracts.put(
                    operation,
                    new EndpointContract(
                        generatedOperation.method(),
                        generatedOperation.path(),
                        generatedOperation.queryParamNames(),
                        generatedOperation.hasRequestBody(),
                        generatedOperation.requestBodyRequired(),
                        types.responseType(),
                        generatedOperation.operationId()
                    )
                );
                continue;
            }
            extractPath(body).ifPresent(path -> contracts.put(
                operation,
                new EndpointContract(
                    httpMethod(body),
                    path,
                    queryParamNames(body),
                    body.contains("body:"),
                    body.contains("body:") && !inputTypeAllowsUndefined(types.inputType()),
                    types.responseType(),
                    null
                )
            ));
        }
        Matcher generatedOperationMatcher = FRONTEND_GENERATED_OPERATION_PATTERN.matcher(source);
        while (generatedOperationMatcher.find()) {
            String operation = generatedOperationMatcher.group(1);
            String generatedOperationId = generatedOperationMatcher.group(2);
            OperationTypes types = operationTypes.getOrDefault(operation, new OperationTypes("", ""));
            contracts.put(
                operation,
                generatedContract(generatedOperations, generatedOperationId, types)
            );
        }
        return contracts;
    }

    public static Map<String, String> endpointKeys() throws IOException {
        Map<String, String> contracts = new LinkedHashMap<>();
        endpointContracts().forEach((operation, contract) -> contracts.put(operation, contract.endpointKey()));
        return contracts;
    }

    public static List<String> apiPathLiteralsOutsideClientAndContracts() throws IOException {
        Path root = sourceRoot();
        List<String> literals = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(FrontendApiContractCatalog::isProductionSource)
                .filter(file -> !isClientOrContract(root, file))
                .sorted()
                .toList()) {
                String source = Files.readString(path);
                Matcher matcher = FRONTEND_API_PATH_LITERAL_PATTERN.matcher(source);
                while (matcher.find()) {
                    literals.add(relativePath(root, path) + " -> " + matcher.group(1));
                }
            }
        }
        return literals;
    }

    public static Path sourcePath(String first, String second) {
        Path candidate = Path.of("..", "repoguard-frontend", "src", first, second);
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path rootCandidate = Path.of("repoguard-frontend", "src", first, second);
        if (Files.exists(rootCandidate)) {
            return rootCandidate;
        }
        return candidate;
    }

    public static String normalizeFrontendResponseType(String responseType) {
        return normalizeResponseType(responseType, false);
    }

    public static String normalizeJavaResponseType(String responseType) {
        return normalizeResponseType(responseType, true);
    }

    private static Map<String, OperationTypes> apiOperationTypes(String source) {
        Matcher matcher = FRONTEND_API_TYPE_OPERATION_PATTERN.matcher(source);
        Map<String, OperationTypes> operationTypes = new LinkedHashMap<>();
        while (matcher.find()) {
            List<String> arguments = apiOperationTypeArguments(source, matcher.end());
            operationTypes.put(matcher.group(1), new OperationTypes(arguments.get(0), arguments.get(1)));
        }
        return operationTypes;
    }

    private static List<String> apiOperationTypeArguments(String source, int start) {
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

    private static boolean inputTypeAllowsUndefined(String inputType) {
        return "undefined".equals(inputType) || inputType.contains("| undefined");
    }

    private static Optional<String> extractPath(String operationBody) {
        Matcher literalPathMatcher = FRONTEND_LITERAL_PATH_PATTERN.matcher(operationBody);
        if (literalPathMatcher.find()) {
            return Optional.of(literalPathMatcher.group(1));
        }
        Matcher templatePathMatcher = FRONTEND_TEMPLATE_PATH_PATTERN.matcher(operationBody);
        if (templatePathMatcher.find()) {
            return Optional.of(normalizeTemplatePath(templatePathMatcher.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<String> generatedOperationId(String operationBody) {
        Matcher matcher = FRONTEND_GENERATED_ENDPOINT_PATTERN.matcher(operationBody);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static EndpointContract generatedContract(
        Map<String, OpenApiGeneratedClientDryRun.GeneratedOperation> generatedOperations,
        String generatedOperationId,
        OperationTypes types
    ) {
        OpenApiGeneratedClientDryRun.GeneratedOperation generatedOperation =
            generatedOperations.get(generatedOperationId);
        if (generatedOperation == null) {
            throw new IllegalStateException(
                "Frontend generated endpoint is missing from reviewed OpenAPI JSON: " + generatedOperationId
            );
        }
        return new EndpointContract(
            generatedOperation.method(),
            generatedOperation.path(),
            generatedOperation.queryParamNames(),
            generatedOperation.hasRequestBody(),
            generatedOperation.requestBodyRequired(),
            types.responseType(),
            generatedOperation.operationId()
        );
    }

    private static Map<String, OpenApiGeneratedClientDryRun.GeneratedOperation> generatedOperationsById()
        throws IOException {
        Map<String, Object> document = OpenApiContractDocument.fromJson(Files.readString(openApiPath()));
        return OpenApiGeneratedClientDryRun.operations(document).values().stream()
            .collect(Collectors.toMap(
                OpenApiGeneratedClientDryRun.GeneratedOperation::operationId,
                Function.identity()
            ));
    }

    private static String httpMethod(String operationBody) {
        Matcher methodMatcher = FRONTEND_METHOD_PATTERN.matcher(operationBody);
        return methodMatcher.find() ? methodMatcher.group(1) : "GET";
    }

    private static List<String> queryParamNames(String operationBody) {
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

    private static String normalizeTemplatePath(String path) {
        return path
            .replace("${idSegment(input.findingId)}", "{findingId}")
            .replace("${idSegment(input.policyVersion)}", "{policyVersion}")
            .replace("${idSegment(input.snapshotId)}", "{snapshotId}")
            .replace("${idSegment(input.jobId)}", "{jobId}")
            .replace("${idSegment(input.taskId)}", "{taskId}")
            .replace("${idSegment(input.id)}", "{id}");
    }

    private static String normalizeResponseType(String responseType, boolean javaType) {
        String type = responseType.replaceAll("\\s+", "");
        if (type.endsWith("[]")) {
            return normalizeResponseType(type.substring(0, type.length() - 2), javaType) + "[]";
        }
        if (type.startsWith("List<") && type.endsWith(">")) {
            return normalizeResponseType(genericContent(type), javaType) + "[]";
        }
        if (type.startsWith("Required<") && type.endsWith(">")) {
            return normalizeResponseType(genericContent(type), javaType);
        }
        if (type.startsWith("PageResponse<") && type.endsWith(">")) {
            return "PageResponse<" + normalizeResponseType(genericContent(type), javaType) + ">";
        }
        if (!javaType) {
            return type;
        }
        return javaResponseTypeAliases().getOrDefault(type, defaultJavaResponseTypeName(type));
    }

    private static String genericContent(String type) {
        return type.substring(type.indexOf('<') + 1, type.length() - 1);
    }

    private static String defaultJavaResponseTypeName(String type) {
        return switch (type) {
            case "Void" -> "void";
            case "String" -> "string";
            default -> type.endsWith("Dto") ? type.substring(0, type.length() - "Dto".length()) : type;
        };
    }

    private static Map<String, String> javaResponseTypeAliases() {
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
            Map.entry("ReviewTaskSummary", "ReviewTaskSummary"),
            Map.entry("ReviewTaskStatusResponse", "ReviewTaskStatus"),
            Map.entry("ReviewTimelineItem", "TimelineItem"),
            Map.entry("ServiceIntegrationConfigDto", "ServiceIntegrationConfig"),
            Map.entry("SystemSettingsDto", "SystemSettings"),
            Map.entry("UserManagementItemDto", "ManagedUser"),
            Map.entry("UserOperationAuditDto", "UserOperationAudit")
        );
    }

    private static Path contractsPath() {
        return sourcePath("api", "contracts.ts");
    }

    private static Path openApiPath() {
        Path backendCandidate = Path.of("src/test/resources/contracts/openapi.json");
        if (Files.exists(backendCandidate)) {
            return backendCandidate;
        }
        return Path.of("repoguard-backend", "src/test/resources/contracts/openapi.json");
    }

    private static Path sourceRoot() {
        Path candidate = Path.of("..", "repoguard-frontend", "src");
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path rootCandidate = Path.of("repoguard-frontend", "src");
        if (Files.exists(rootCandidate)) {
            return rootCandidate;
        }
        return candidate;
    }

    private static boolean isProductionSource(Path path) {
        String filename = path.getFileName().toString();
        return (filename.endsWith(".ts") || filename.endsWith(".vue"))
            && !filename.endsWith(".test.ts")
            && !filename.endsWith(".spec.ts");
    }

    private static boolean isClientOrContract(Path root, Path path) {
        String relativePath = relativePath(root, path);
        return "api/client.ts".equals(relativePath)
            || "api/contracts.ts".equals(relativePath)
            || "api/generated/openapiOperations.ts".equals(relativePath);
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private record OperationTypes(String inputType, String responseType) {
    }

    public record EndpointContract(
        String httpMethod,
        String path,
        List<String> queryParamNames,
        boolean hasRequestBody,
        boolean requestBodyRequired,
        String responseType,
        String generatedOperationId
    ) {

        public String endpointKey() {
            return httpMethod + " " + path;
        }
    }
}
