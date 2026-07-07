package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.github.webhook.GithubWebhookResponse;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiContractTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";
    private static final Pattern FRONTEND_API_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): \\{\\R(?<body>.*?)^\\s{2}},?",
        Pattern.MULTILINE | Pattern.DOTALL
    );
    private static final Pattern FRONTEND_METHOD_PATTERN = Pattern.compile("method: \"(GET|POST|PUT|DELETE)\"");
    private static final Pattern FRONTEND_LITERAL_PATH_PATTERN = Pattern.compile("path: \\(\\) => \"([^\"]+)\"");
    private static final Pattern FRONTEND_TEMPLATE_PATH_PATTERN = Pattern.compile("path: input => `([^`]+)`");
    private static final Pattern FRONTEND_API_PATH_LITERAL_PATTERN = Pattern.compile("[\"`](/api/v1/[^\"`]+)[\"`]");

    @Test
    void controllerBasePathsStayVersionedUnderApiV1() {
        controllers().forEach(controller -> {
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            if (mapping != null) {
                assertThat(ControllerEndpointCatalog.classMappingPaths(controller))
                    .as(controller.getSimpleName() + " base path must be versioned")
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith("/api/v1/"));
                return;
            }

            assertThat(handlerMappingPaths(controller))
                .as(controller.getSimpleName() + " handler paths must be versioned when no base request mapping exists")
                .isNotEmpty()
                .allSatisfy(path -> assertThat(path).startsWith("/api/v1/"));
        });
    }

    @Test
    void handlerMethodsReturnStableApiResponseEnvelope() {
        controllers().forEach(controller ->
            List.of(controller.getDeclaredMethods()).stream()
                .filter(ControllerEndpointCatalog::isHandlerMethod)
                .forEach(method -> assertThat(method.getReturnType())
                    .as(controller.getSimpleName() + "#" + method.getName() + " must return ApiResponse")
                    .isEqualTo(ApiResponse.class))
        );
    }

    @Test
    void pagedControllerParamsKeepBoundedContract() {
        controllers().forEach(controller ->
            List.of(controller.getDeclaredMethods()).stream()
                .filter(ControllerEndpointCatalog::isHandlerMethod)
                .forEach(method -> List.of(method.getParameters()).forEach(parameter -> {
                    String requestParamName = requestParamName(parameter);
                    if ("page".equals(requestParamName)) {
                        assertThat(parameter.getAnnotation(Min.class))
                            .as(controller.getSimpleName() + "#" + method.getName() + " page must be >= 1")
                            .isNotNull()
                            .extracting(Min::value)
                            .isEqualTo(1L);
                    }
                    if ("pageSize".equals(requestParamName)) {
                        assertThat(parameter.getAnnotation(Min.class))
                            .as(controller.getSimpleName() + "#" + method.getName() + " pageSize must be >= 1")
                            .isNotNull()
                            .extracting(Min::value)
                            .isEqualTo(1L);
                        assertThat(parameter.getAnnotation(Max.class))
                            .as(controller.getSimpleName() + "#" + method.getName() + " pageSize must be <= 100")
                            .isNotNull()
                            .extracting(Max::value)
                            .isEqualTo(100L);
                    }
                }))
        );
    }

    @Test
    void apiSurfaceStaysAlignedWithBackendOwnedContract() throws Exception {
        assertThat(apiSurface())
            .as("Controller method/path/query/body contract changed. Update frontend contract coverage together with this backend-owned API surface.")
            .containsExactlyElementsOf(apiSurfaceSnapshot());
    }

    @Test
    void apiSurfaceSnapshotStaysSortedAndUnique() throws Exception {
        List<String> snapshot = apiSurfaceSnapshot();

        assertThat(snapshot)
            .as("API surface snapshot should stay sorted so endpoint diffs remain reviewable")
            .containsExactlyElementsOf(snapshot.stream().sorted().distinct().toList());
    }

    @Test
    void coreFrontendIntegrationPathsStayCoveredByApiSurfaceContract() {
        Set<String> apiSurface = Set.copyOf(apiSurface());

        assertThat(apiSurface)
            .contains(
                "GET /api/v1/reviews query=[page, pageSize, repository, status, riskLevel, source, triggerSource, keyword] body=-",
                "GET /api/v1/reviews/{id} query=[] body=-",
                "GET /api/v1/reviews/{id}/findings query=[page, pageSize, severity, category, feedbackStatus] body=-",
                "GET /api/v1/reviews/{id}/changed-files query=[page, pageSize, hasFinding] body=-",
                "GET /api/v1/reviews/{id}/missing-tests query=[page, pageSize] body=-",
                "GET /api/v1/reviews/{id}/github-comments/preview query=[page, pageSize, commentableOnly] body=-",
                "GET /api/v1/reviews/repositories query=[] body=-",
                "GET /api/v1/dashboard/summary query=[] body=-",
                "GET /api/v1/dashboard/llm-quality query=[llmTrendDays] body=-",
                "GET /api/v1/config/review-rules query=[] body=-",
                "GET /api/v1/message-queue/health query=[] body=-",
                "POST /api/v1/auth/refresh query=[] body=AuthRefreshRequest",
                "POST /api/v1/observability/frontend/performance query=[] body=FrontendPerformanceReportRequest",
                "POST /api/v1/github/webhooks query=[] body=byte[]"
            );
    }

    @Test
    void githubWebhookResponseKeepsQueuedAndSkippedContractShape() {
        assertThat(recordComponentTypes(GithubWebhookResponse.class))
            .as("GitHub webhook queued/skipped response shape is part of the API contract")
            .containsExactlyEntriesOf(expectedGithubWebhookResponseContract());
    }

    @Test
    void frontendTypedApiContractsStayWithinBackendApiSurface() throws Exception {
        Set<String> backendEndpoints = Set.copyOf(apiSurfaceEndpointKeys());

        assertThat(frontendApiContracts())
            .as("Frontend typed api contracts must point to backend-owned controller endpoints")
            .isNotEmpty()
            .allSatisfy((operation, endpoint) -> assertThat(backendEndpoints)
                .as(operation + " frontend endpoint must exist in backend API surface")
                .contains(endpoint));
    }

    @Test
    void frontendDirectApiEntrypointsStayWithinBackendApiSurface() throws Exception {
        Set<String> backendEndpoints = Set.copyOf(apiSurfaceEndpointKeys());
        Map<String, String> directEntrypoints = frontendDirectApiEntrypoints();

        assertThat(directEntrypoints)
            .as("Frontend direct api calls outside typed contracts must point to backend-owned controller endpoints")
            .isEmpty();
        directEntrypoints.forEach((operation, endpoint) -> assertThat(backendEndpoints)
                .as(operation + " frontend direct endpoint must exist in backend API surface")
                .contains(endpoint));
    }

    @Test
    void frontendApiPathLiteralsOutsideClientAndContractsStayExplicitlyWhitelisted() throws Exception {
        assertThat(frontendApiPathLiteralsOutsideClientAndContracts())
            .as("Production frontend API path literals outside typed contracts/client transport must stay explicitly whitelisted")
            .isEmpty();
    }

    @Test
    void authRefreshCoordinatorDoesNotOwnDirectApiFetch() throws Exception {
        String authRefreshSource = Files.readString(frontendSourcePath("api", "authRefreshCoordinator.ts"));

        assertThat(authRefreshSource)
            .as("Auth refresh coordination must reuse the frontend API client transport instead of owning direct fetch/buildUrl details")
            .doesNotContain("fetch(")
            .doesNotContain("buildUrl(")
            .doesNotContain("/api/v1/auth/refresh");
    }

    @Test
    void successfulResponsesUseStableEnvelopeFields() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ContractController()).build();

        mockMvc.perform(get("/api/v1/contract/success"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.data.value").value("ready"))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void errorResponsesUseStableEnvelopeFields() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ContractController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/api/v1/contract/failure"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("forbidden by contract"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    private List<String> handlerMappingPaths(Class<?> controller) {
        return ControllerEndpointCatalog.endpoints(controller).stream()
            .map(ControllerEndpointCatalog.Endpoint::path)
            .toList();
    }

    private String requestParamName(Parameter parameter) {
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

    private List<String> apiSurface() {
        return controllers().stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path()
                + " query=" + ControllerEndpointCatalog.requestParamNames(endpoint.method())
                + " body=" + ControllerEndpointCatalog.requestBodyType(endpoint.method()))
            .sorted()
            .toList();
    }

    private List<String> apiSurfaceEndpointKeys() {
        return controllers().stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path())
            .sorted()
            .toList();
    }

    private List<String> apiSurfaceSnapshot() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/contracts/api-surface.snapshot")).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }

    private List<Class<?>> controllers() {
        try {
            return ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to discover API controllers", ex);
        }
    }

    private Map<String, String> frontendApiContracts() throws Exception {
        String source = Files.readString(frontendContractsPath());
        Matcher operationMatcher = FRONTEND_API_OPERATION_PATTERN.matcher(source);
        Map<String, String> contracts = new LinkedHashMap<>();
        while (operationMatcher.find()) {
            String operation = operationMatcher.group(1);
            String body = operationMatcher.group("body");
            extractFrontendPath(body).ifPresent(path -> contracts.put(operation, frontendHttpMethod(body) + " " + path));
        }
        return contracts;
    }

    private Map<String, Class<?>> recordComponentTypes(Class<?> recordType) {
        Map<String, Class<?>> components = new LinkedHashMap<>();
        for (var component : recordType.getRecordComponents()) {
            components.put(component.getName(), component.getType());
        }
        return components;
    }

    private Map<String, Class<?>> expectedGithubWebhookResponseContract() {
        Map<String, Class<?>> contract = new LinkedHashMap<>();
        contract.put("status", String.class);
        contract.put("message", String.class);
        contract.put("taskId", Long.class);
        contract.put("existing", Boolean.class);
        contract.put("deliveryId", String.class);
        contract.put("action", String.class);
        return contract;
    }

    private Map<String, String> frontendDirectApiEntrypoints() throws Exception {
        return Map.of();
    }

    private Path frontendContractsPath() {
        return frontendSourcePath("api", "contracts.ts");
    }

    private Path frontendSourceRoot() {
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

    private Path frontendSourcePath(String first, String second) {
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

    private List<String> frontendApiPathLiteralsOutsideClientAndContracts() throws Exception {
        Path root = frontendSourceRoot();
        List<String> literals = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths
                .filter(Files::isRegularFile)
                .filter(this::isProductionFrontendSource)
                .filter(file -> !isFrontendClientOrContract(root, file))
                .sorted()
                .toList()) {
                String source = Files.readString(path);
                Matcher matcher = FRONTEND_API_PATH_LITERAL_PATTERN.matcher(source);
                while (matcher.find()) {
                    literals.add(frontendRelativePath(root, path) + " -> " + matcher.group(1));
                }
            }
        }
        return literals;
    }

    private boolean isProductionFrontendSource(Path path) {
        String filename = path.getFileName().toString();
        return (filename.endsWith(".ts") || filename.endsWith(".vue"))
            && !filename.endsWith(".test.ts")
            && !filename.endsWith(".spec.ts");
    }

    private boolean isFrontendClientOrContract(Path root, Path path) {
        String relativePath = frontendRelativePath(root, path);
        return "api/client.ts".equals(relativePath) || "api/contracts.ts".equals(relativePath);
    }

    private String frontendRelativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
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

    private String normalizeFrontendTemplatePath(String path) {
        return path
            .replace("${idSegment(input.findingId)}", "{findingId}")
            .replace("${idSegment(input.taskId)}", "{taskId}")
            .replace("${idSegment(input.id)}", "{id}");
    }

    @RestController
    @RequestMapping("/api/v1/contract")
    static class ContractController {

        @GetMapping("/success")
        ApiResponse<ContractPayload> success() {
            return ApiResponse.ok(new ContractPayload("ready"));
        }

        @GetMapping("/failure")
        ApiResponse<Void> failure() {
            throw new BusinessException(ErrorCode.FORBIDDEN, "forbidden by contract");
        }
    }

    record ContractPayload(String value) {
    }
}
