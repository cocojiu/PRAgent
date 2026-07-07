package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.FrontendApiContractCatalog;
import com.repoguard.agent.testsupport.OpenApiContractDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenApiGeneratedClientContractTest {

    private static final Set<String> SERVER_ONLY_ENDPOINTS = Set.of(
        "POST /api/v1/auth/refresh",
        "POST /api/v1/github/webhooks"
    );

    @Test
    void reviewedOpenApiJsonCanDriveFrontendTypedClientCoverage() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();
        Set<String> frontendOrServerOnlyEndpoints = new java.util.LinkedHashSet<>();
        FrontendApiContractCatalog.endpointContracts().values().stream()
            .map(FrontendApiContractCatalog.EndpointContract::endpointKey)
            .forEach(frontendOrServerOnlyEndpoints::add);
        frontendOrServerOnlyEndpoints.addAll(SERVER_ONLY_ENDPOINTS);

        assertThat(generatedOperations.keySet())
            .as("Reviewed OpenAPI JSON operations must be covered by the frontend typed API contract or server-only allowlist")
            .allSatisfy(endpoint -> assertThat(frontendOrServerOnlyEndpoints).contains(endpoint));
    }

    @Test
    void frontendTypedClientStaysWithinReviewedOpenApiJson() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();

        assertThat(FrontendApiContractCatalog.endpointContracts())
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
                assertThat(FrontendApiContractCatalog.normalizeFrontendResponseType(frontendContract.responseType()))
                    .as(operation + " response type must match OpenAPI JSON response data type")
                    .isEqualTo(FrontendApiContractCatalog.normalizeJavaResponseType(generatedOperation.responseDataType()));
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

}
