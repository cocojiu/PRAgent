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
import com.repoguard.agent.testsupport.FrontendApiContractCatalog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiContractTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";

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
    void pathVariablesStayAlignedWithControllerParameters() {
        controllers().stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .forEach(endpoint -> assertThat(ControllerEndpointCatalog.pathVariableNames(endpoint.method()))
                .as(ControllerEndpointCatalog.endpointId(endpoint.controller(), endpoint.method())
                    + " path variables must match " + endpoint.path())
                .containsExactlyElementsOf(ControllerEndpointCatalog.pathTemplateVariableNames(endpoint.path())));
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
                "GET /api/v1/reviews query=[page, pageSize, repository, status, riskLevel, source, triggerSource, keyword, cursor] body=- bodyRequired=false",
                "GET /api/v1/reviews/{id} query=[] body=- bodyRequired=false",
                "GET /api/v1/reviews/{id}/findings query=[page, pageSize, severity, category, feedbackStatus] body=- bodyRequired=false",
                "GET /api/v1/reviews/{id}/changed-files query=[page, pageSize, hasFinding] body=- bodyRequired=false",
                "GET /api/v1/reviews/{id}/missing-tests query=[page, pageSize] body=- bodyRequired=false",
                "GET /api/v1/reviews/{id}/github-comments/preview query=[page, pageSize, commentableOnly] body=- bodyRequired=false",
                "GET /api/v1/reviews/repositories query=[] body=- bodyRequired=false",
                "GET /api/v1/dashboard/summary query=[] body=- bodyRequired=false",
                "GET /api/v1/dashboard/llm-quality query=[llmTrendDays] body=- bodyRequired=false",
                "GET /api/v1/config/review-rules query=[] body=- bodyRequired=false",
                "GET /api/v1/message-queue/health query=[] body=- bodyRequired=false",
                "POST /api/v1/auth/refresh query=[] body=AuthRefreshRequest bodyRequired=false",
                "POST /api/v1/observability/frontend/performance query=[] body=FrontendPerformanceReportRequest bodyRequired=true",
                "POST /api/v1/github/webhooks query=[] body=byte[] bodyRequired=true"
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
    void backendApiSurfaceStaysCoveredByFrontendTypedContractsOrExplicitServerOnlyEntrypoints() throws Exception {
        Set<String> coveredEndpoints = frontendCoveredOrServerOnlyEndpointKeys();

        assertThat(apiSurfaceEndpointKeys())
            .as("Backend-owned controller endpoints must be covered by frontend typed contracts unless explicitly server-only")
            .allSatisfy(endpoint -> assertThat(coveredEndpoints)
                .as(endpoint + " must be covered by frontend typed contract or server-only allowlist")
                .contains(endpoint));
    }

    @Test
    void frontendTypedApiContractsKeepTransportShapeAlignedWithBackendApiSurface() throws Exception {
        Map<String, BackendEndpointContract> backendContracts = backendEndpointContracts();

        assertThat(frontendEndpointContracts())
            .as("Frontend typed api contracts must keep method/query/body shape aligned with backend API surface")
            .isNotEmpty()
            .allSatisfy((operation, frontendContract) -> {
                BackendEndpointContract backendContract = backendContracts.get(frontendContract.endpointKey());

                assertThat(backendContract)
                    .as(operation + " frontend endpoint must exist in backend API surface")
                    .isNotNull();
                assertThat(backendContract.queryParamNames())
                    .as(operation + " frontend query params must be backed by controller @RequestParam names")
                    .containsAll(frontendContract.queryParamNames());
                assertThat(frontendContract.hasRequestBody())
                    .as(operation + " frontend body usage must match backend @RequestBody contract")
                    .isEqualTo(backendContract.hasRequestBody());
                assertThat(frontendContract.requestBodyRequired())
                    .as(operation + " frontend input optionality must match backend @RequestBody(required=...)")
                    .isEqualTo(backendContract.requestBodyRequired());
            });
    }

    @Test
    void frontendTypedApiContractsKeepResponseDataAlignedWithBackendApiSurface() throws Exception {
        Map<String, BackendEndpointContract> backendContracts = backendEndpointContracts();

        assertThat(frontendEndpointContracts())
            .as("Frontend typed api response contracts must match backend ApiResponse<T> data types")
            .isNotEmpty()
            .allSatisfy((operation, frontendContract) -> {
                BackendEndpointContract backendContract = backendContracts.get(frontendContract.endpointKey());

                assertThat(backendContract)
                    .as(operation + " frontend endpoint must exist in backend API surface")
                    .isNotNull();
                assertThat(normalizeFrontendResponseType(frontendContract.responseType()))
                    .as(operation + " frontend response type must match backend ApiResponse<T> data type")
                    .isEqualTo(normalizeBackendResponseType(backendContract.responseDataType()));
            });
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
        String authRefreshSource = Files.readString(
            FrontendApiContractCatalog.sourcePath("api", "authRefreshCoordinator.ts")
        );

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
                + " body=" + ControllerEndpointCatalog.requestBodyType(endpoint.method())
                + " bodyRequired=" + ControllerEndpointCatalog.requestBodyRequired(endpoint.method()))
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

    private Set<String> frontendCoveredOrServerOnlyEndpointKeys() throws Exception {
        Set<String> endpoints = new java.util.LinkedHashSet<>(frontendApiContracts().values());
        endpoints.addAll(serverOnlyApiEndpointKeys());
        return endpoints;
    }

    private Set<String> serverOnlyApiEndpointKeys() {
        return Set.of(
            "POST /api/v1/auth/refresh",
            "GET /api/v1/enterprise/tenants",
            "GET /api/v1/enterprise/tenants/{tenantKey}",
            "GET /api/v1/enterprise/tenants/{tenantKey}/quota",
            "POST /api/v1/enterprise/tenants",
            "POST /api/v1/github/webhooks",
            "PUT /api/v1/enterprise/tenants/{tenantKey}/identities",
            "PUT /api/v1/enterprise/tenants/{tenantKey}/memberships",
            "PUT /api/v1/enterprise/tenants/{tenantKey}/repositories",
            "PUT /api/v1/enterprise/tenants/{tenantKey}/status",
            "PUT /api/v1/enterprise/tenants/{tenantKey}/quota",
            "GET /api/v1/reviews/{taskId}/attempts/{attemptId}/changed-files",
            "GET /api/v1/reviews/{taskId}/attempts/{attemptId}/findings"
        );
    }

    private Map<String, BackendEndpointContract> backendEndpointContracts() {
        Map<String, BackendEndpointContract> contracts = new LinkedHashMap<>();
        controllers().stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .sorted((left, right) -> (left.httpMethod() + " " + left.path()).compareTo(right.httpMethod() + " " + right.path()))
            .forEach(endpoint -> contracts.put(
                endpoint.httpMethod() + " " + endpoint.path(),
                new BackendEndpointContract(
                    ControllerEndpointCatalog.requestParamNames(endpoint.method()),
                    ControllerEndpointCatalog.hasRequestBody(endpoint.method()),
                    ControllerEndpointCatalog.requestBodyRequired(endpoint.method()),
                    responseDataType(endpoint.method())
                )
            ));
        return contracts;
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
        return FrontendApiContractCatalog.endpointKeys();
    }

    private Map<String, FrontendApiContractCatalog.EndpointContract> frontendEndpointContracts() throws Exception {
        return FrontendApiContractCatalog.endpointContracts();
    }

    private String responseDataType(java.lang.reflect.Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType parameterizedType
            && parameterizedType.getRawType() == ApiResponse.class) {
            return javaTypeName(parameterizedType.getActualTypeArguments()[0]);
        }
        return method.getReturnType().getSimpleName();
    }

    private String javaTypeName(Type type) {
        if (type instanceof Class<?> typeClass) {
            return typeClass.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            String rawName = rawType instanceof Class<?> rawClass ? rawClass.getSimpleName() : rawType.getTypeName();
            return rawName + "<" + List.of(parameterizedType.getActualTypeArguments()).stream()
                .map(this::javaTypeName)
                .reduce((left, right) -> left + "," + right)
                .orElse("") + ">";
        }
        return type.getTypeName();
    }

    private String normalizeBackendResponseType(String responseType) {
        return FrontendApiContractCatalog.normalizeJavaResponseType(responseType);
    }

    private String normalizeFrontendResponseType(String responseType) {
        return FrontendApiContractCatalog.normalizeFrontendResponseType(responseType);
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

    private List<String> frontendApiPathLiteralsOutsideClientAndContracts() throws Exception {
        return FrontendApiContractCatalog.apiPathLiteralsOutsideClientAndContracts();
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

    record BackendEndpointContract(
        List<String> queryParamNames,
        boolean hasRequestBody,
        boolean requestBodyRequired,
        String responseDataType
    ) {
    }

}
