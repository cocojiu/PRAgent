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

    private static final Pattern FRONTEND_API_OPERATION_PATTERN = Pattern.compile(
        "^\\s{2}([a-zA-Z0-9]+): \\{\\R(?<body>.*?)^\\s{2}},?",
        Pattern.MULTILINE | Pattern.DOTALL
    );
    private static final Pattern FRONTEND_METHOD_PATTERN = Pattern.compile("method: \"(GET|POST|PUT|DELETE)\"");
    private static final Pattern FRONTEND_LITERAL_PATH_PATTERN = Pattern.compile("path: \\(\\) => \"([^\"]+)\"");
    private static final Pattern FRONTEND_TEMPLATE_PATH_PATTERN = Pattern.compile("path: input => `([^`]+)`");
    private static final Pattern FRONTEND_API_PATH_LITERAL_PATTERN = Pattern.compile("[\"`](/api/v1/[^\"`]+)[\"`]");
    private static final List<Class<?>> CONTROLLERS = List.of(
        AuthController.class,
        CacheStatsController.class,
        DashboardController.class,
        DataRetentionController.class,
        FrontendPerformanceController.class,
        GithubWebhookController.class,
        MessageQueueHealthController.class,
        NotificationController.class,
        NotificationIntegrationController.class,
        ReviewController.class,
        SystemHealthController.class,
        SystemConfigController.class,
        UserManagementController.class
    );

    private static final List<String> EXPECTED_API_SURFACE = List.of(
        "GET /api/v1/auth/me query=[] body=-",
        "GET /api/v1/cache/stats query=[] body=-",
        "GET /api/v1/config/integrations/github query=[] body=-",
        "GET /api/v1/config/integrations/mysql query=[] body=-",
        "GET /api/v1/config/integrations/rabbitmq query=[] body=-",
        "GET /api/v1/config/notification-bindings query=[page, pageSize, organization, repository, provider] body=-",
        "GET /api/v1/config/review-policy query=[] body=-",
        "GET /api/v1/config/review-rules query=[] body=-",
        "GET /api/v1/config/system-settings query=[] body=-",
        "GET /api/v1/dashboard/high-risk-reviews query=[] body=-",
        "GET /api/v1/dashboard/llm-quality query=[llmTrendDays] body=-",
        "GET /api/v1/dashboard/overview query=[llmTrendDays] body=-",
        "GET /api/v1/dashboard/review-trend query=[] body=-",
        "GET /api/v1/dashboard/risk-distribution query=[] body=-",
        "GET /api/v1/dashboard/rules query=[] body=-",
        "GET /api/v1/dashboard/summary query=[] body=-",
        "GET /api/v1/message-queue/health query=[] body=-",
        "GET /api/v1/notification-deliveries query=[page, pageSize, status, taskId] body=-",
        "GET /api/v1/notification-events query=[page, pageSize, status, taskId] body=-",
        "GET /api/v1/notifications query=[] body=-",
        "GET /api/v1/reviews query=[page, pageSize, repository, status, riskLevel, source, triggerSource, keyword] body=-",
        "GET /api/v1/reviews/github/pull-requests query=[] body=-",
        "GET /api/v1/reviews/repositories query=[] body=-",
        "GET /api/v1/reviews/{id} query=[] body=-",
        "GET /api/v1/reviews/{id}/changed-files query=[page, pageSize, hasFinding] body=-",
        "GET /api/v1/reviews/{id}/findings query=[page, pageSize, severity, category, feedbackStatus] body=-",
        "GET /api/v1/reviews/{id}/github-comments/preview query=[page, pageSize, commentableOnly] body=-",
        "GET /api/v1/reviews/{id}/github-comments/publications query=[page, pageSize, status] body=-",
        "GET /api/v1/reviews/{id}/missing-tests query=[page, pageSize] body=-",
        "GET /api/v1/reviews/{id}/status query=[] body=-",
        "GET /api/v1/reviews/{id}/timeline query=[limit] body=-",
        "GET /api/v1/system/health/summary query=[] body=-",
        "GET /api/v1/users query=[] body=-",
        "GET /api/v1/users/audits query=[] body=-",
        "POST /api/v1/auth/login query=[] body=AuthLoginRequest",
        "POST /api/v1/auth/logout query=[] body=AuthLogoutRequest",
        "POST /api/v1/auth/refresh query=[] body=AuthRefreshRequest",
        "POST /api/v1/auth/refresh-token/reset query=[] body=AuthRefreshTokenResetRequest",
        "POST /api/v1/auth/register query=[] body=AuthRegisterRequest",
        "POST /api/v1/config/data-retention/cleanup query=[] body=DataRetentionCleanupRequest",
        "POST /api/v1/config/integrations/github/test query=[] body=GithubIntegrationConfigRequest",
        "POST /api/v1/config/integrations/mysql/test query=[] body=ServiceIntegrationConfigRequest",
        "POST /api/v1/config/integrations/rabbitmq/test query=[] body=ServiceIntegrationConfigRequest",
        "POST /api/v1/config/notification-bindings query=[] body=NotificationBindingRequest",
        "POST /api/v1/config/notification-bindings/{id}/test query=[] body=-",
        "POST /api/v1/config/review-policy/test query=[] body=ReviewPolicyConfigRequest",
        "POST /api/v1/config/review-rules query=[] body=ReviewRuleConfigRequest",
        "POST /api/v1/config/secrets/re-encryption query=[] body=SecretReEncryptionRequest",
        "POST /api/v1/github/webhooks query=[] body=byte[]",
        "POST /api/v1/message-queue/tasks/{taskId}/requeue query=[] body=-",
        "POST /api/v1/notification-events/{id}/retry query=[] body=-",
        "POST /api/v1/observability/frontend/performance query=[] body=FrontendPerformanceReportRequest",
        "POST /api/v1/reviews/{id}/findings/{findingId}/feedback query=[] body=FindingFeedbackRequest",
        "POST /api/v1/reviews/{id}/github-comments query=[] body=-",
        "POST /api/v1/reviews/{id}/human-review query=[] body=HumanReviewRequest",
        "POST /api/v1/reviews/{id}/retry query=[] body=-",
        "POST /api/v1/reviews/manual query=[] body=ManualReviewRequest",
        "POST /api/v1/users query=[] body=UserCreateRequest",
        "PUT /api/v1/config/integrations/github query=[] body=GithubIntegrationConfigRequest",
        "PUT /api/v1/config/integrations/mysql query=[] body=ServiceIntegrationConfigRequest",
        "PUT /api/v1/config/integrations/rabbitmq query=[] body=ServiceIntegrationConfigRequest",
        "PUT /api/v1/config/notification-bindings/{id} query=[] body=NotificationBindingRequest",
        "PUT /api/v1/config/notification-bindings/{id}/status query=[] body=NotificationBindingStatusRequest",
        "PUT /api/v1/config/review-policy query=[] body=ReviewPolicyConfigRequest",
        "PUT /api/v1/config/review-rules/{id} query=[] body=ReviewRuleConfigRequest",
        "PUT /api/v1/config/review-rules/{id}/status query=[] body=ReviewRuleStatusRequest",
        "PUT /api/v1/config/system-settings query=[] body=SystemSettingsRequest",
        "PUT /api/v1/users/{id}/role query=[] body=UserRoleUpdateRequest",
        "PUT /api/v1/users/{id}/status query=[] body=UserStatusUpdateRequest",
        "DELETE /api/v1/config/notification-bindings/{id} query=[] body=-"
    );

    @Test
    void controllerBasePathsStayVersionedUnderApiV1() {
        CONTROLLERS.forEach(controller -> {
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
        CONTROLLERS.forEach(controller ->
            List.of(controller.getDeclaredMethods()).stream()
                .filter(ControllerEndpointCatalog::isHandlerMethod)
                .forEach(method -> assertThat(method.getReturnType())
                    .as(controller.getSimpleName() + "#" + method.getName() + " must return ApiResponse")
                    .isEqualTo(ApiResponse.class))
        );
    }

    @Test
    void pagedControllerParamsKeepBoundedContract() {
        CONTROLLERS.forEach(controller ->
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
    void apiSurfaceStaysAlignedWithBackendOwnedContract() {
        assertThat(apiSurface())
            .as("Controller method/path/query/body contract changed. Update frontend contract coverage together with this backend-owned API surface.")
            .containsExactlyElementsOf(EXPECTED_API_SURFACE.stream().sorted().toList());
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
        return CONTROLLERS.stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path()
                + " query=" + ControllerEndpointCatalog.requestParamNames(endpoint.method())
                + " body=" + ControllerEndpointCatalog.requestBodyType(endpoint.method()))
            .sorted()
            .toList();
    }

    private List<String> apiSurfaceEndpointKeys() {
        return CONTROLLERS.stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path())
            .sorted()
            .toList();
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
