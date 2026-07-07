package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.ControllerEndpointCatalog;
import com.repoguard.agent.testsupport.OpenApiContractDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiPathsContractTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";

    @Test
    void openApiPathsSnapshotStaysAlignedWithControllerCatalog() throws Exception {
        assertThat(openApiPaths())
            .as("Controller OpenAPI-like paths contract changed. Update the reviewed snapshot together with frontend/generated-client impact.")
            .containsExactlyElementsOf(openApiPathsSnapshot());
    }

    @Test
    void openApiDocumentStaysAlignedWithReviewedPathsSnapshot() throws Exception {
        Map<String, Object> document = OpenApiContractDocument.fromControllers(controllers());

        assertThat(document)
            .containsEntry("openapi", "3.1.0")
            .containsKeys("info", "paths", "components");
        assertThat(OpenApiContractDocument.operationLines(document))
            .as("Generated OpenAPI document changed. Review generated-client impact together with the paths snapshot.")
            .containsExactlyElementsOf(openApiPathsSnapshot());
    }

    @Test
    void openApiJsonSnapshotStaysAlignedWithGeneratedDocument() throws Exception {
        Map<String, Object> document = OpenApiContractDocument.fromControllers(controllers());
        Map<String, Object> generated = OpenApiContractDocument.fromJson(OpenApiContractDocument.toJson(document));
        Map<String, Object> snapshot = OpenApiContractDocument.fromJson(
            Files.readString(Path.of("src/test/resources/contracts/openapi.json"))
        );

        assertThat(snapshot)
            .as("Generated OpenAPI JSON changed. Update the reviewed JSON snapshot with generated-client impact.")
            .isEqualTo(generated);
        assertThat(OpenApiContractDocument.fromJson(OpenApiContractDocument.toJson(generated)))
            .as("OpenAPI JSON serialization should round-trip without losing contract fields.")
            .isEqualTo(generated);
    }

    @Test
    void openApiComponentsExposeRequestDtoValidationConstraints() {
        Map<String, Object> schemas = map(map(OpenApiContractDocument.fromControllers(controllers()).get("components")).get("schemas"));

        Map<String, Object> registerProperties = map(map(schemas.get("AuthRegisterRequest")).get("properties"));
        assertThat(map(registerProperties.get("username")))
            .containsEntry("type", "string")
            .containsEntry("minLength", 3)
            .containsEntry("maxLength", 64)
            .containsEntry("pattern", "^[A-Za-z0-9_.-]+$");
        assertThat(map(registerProperties.get("email")))
            .containsEntry("format", "email")
            .containsEntry("maxLength", 255);
        assertThat(list(map(schemas.get("AuthRegisterRequest")).get("required")))
            .containsExactly("username", "email", "password", "confirmPassword");

        Map<String, Object> policyProperties = map(map(schemas.get("ReviewPolicyConfigRequest")).get("properties"));
        assertThat(map(policyProperties.get("timeoutSeconds")))
            .containsEntry("type", "integer")
            .containsEntry("minimum", 1L)
            .containsEntry("maximum", 600L);
        assertThat(map(policyProperties.get("temperature")))
            .containsEntry("type", "number")
            .containsEntry("minimum", new java.math.BigDecimal("0.00"))
            .containsEntry("maximum", new java.math.BigDecimal("2.00"));

        Map<String, Object> systemSettingsProperties = map(map(schemas.get("SystemSettingsRequest")).get("properties"));
        assertThat(map(systemSettingsProperties.get("base")))
            .containsEntry("$ref", "#/components/schemas/BaseSettingsRequest")
            .containsEntry("x-valid", true);
    }

    @Test
    void openApiResponsesExposeDataDtoSchemas() {
        Map<String, Object> paths = map(OpenApiContractDocument.fromControllers(controllers()).get("paths"));

        Map<String, Object> loginResponse = response(paths, "/api/v1/auth/login", "post");
        assertThat(loginResponse)
            .containsEntry("x-java-response-envelope", "ApiResponse")
            .containsEntry("x-java-response-data", "AuthResponse");
        assertThat(dataSchema(loginResponse))
            .containsEntry("$ref", "#/components/schemas/AuthResponse");

        Map<String, Object> listReviewsResponse = response(paths, "/api/v1/reviews", "get");
        assertThat(listReviewsResponse)
            .containsEntry("x-java-response-data", "PageResponse<ReviewTaskListItem>");
        Map<String, Object> pageProperties = map(dataSchema(listReviewsResponse).get("properties"));
        assertThat(map(map(pageProperties.get("items")).get("items")))
            .containsEntry("$ref", "#/components/schemas/ReviewTaskListItem");
        assertThat(map(pageProperties.get("total")))
            .containsEntry("type", "integer")
            .containsEntry("format", "int64");

        Map<String, Object> logoutResponse = response(paths, "/api/v1/auth/logout", "post");
        assertThat(logoutResponse)
            .containsEntry("x-java-response-data", "Void");
        assertThat(dataSchema(logoutResponse))
            .containsEntry("type", "null");
    }

    @Test
    void openApiParametersExposeJavaTypesAndValidationConstraints() {
        Map<String, Object> paths = map(OpenApiContractDocument.fromControllers(controllers()).get("paths"));

        Map<String, Object> listReviews = operation(paths, "/api/v1/reviews", "get");
        assertThat(parameterSchema(listReviews, "page"))
            .containsEntry("type", "integer")
            .containsEntry("format", "int32")
            .containsEntry("minimum", 1L)
            .containsEntry("default", 1);
        assertThat(parameterSchema(listReviews, "pageSize"))
            .containsEntry("type", "integer")
            .containsEntry("format", "int32")
            .containsEntry("minimum", 1L)
            .containsEntry("maximum", 100L)
            .containsEntry("default", 20);
        assertThat(parameterSchema(listReviews, "repository"))
            .containsEntry("type", "string")
            .containsEntry("maxLength", 128);

        Map<String, Object> reviewDetail = operation(paths, "/api/v1/reviews/{id}", "get");
        assertThat(parameterSchema(reviewDetail, "id"))
            .containsEntry("type", "integer")
            .containsEntry("format", "int64")
            .containsEntry("minimum", 1L);

        Map<String, Object> changedFiles = operation(paths, "/api/v1/reviews/{id}/changed-files", "get");
        assertThat(parameterSchema(changedFiles, "hasFinding"))
            .containsEntry("type", "boolean");
    }

    @Test
    void openApiDocumentUsesCodegenSafeUniqueOperationIds() {
        List<String> operationIds = OpenApiContractDocument.operationIds(OpenApiContractDocument.fromControllers(controllers()));

        assertThat(operationIds)
            .allMatch(operationId -> operationId.matches("[A-Za-z][A-Za-z0-9]*"))
            .containsExactlyElementsOf(operationIds.stream().distinct().toList());
    }

    @Test
    void openApiPathsSnapshotStaysSortedAndUnique() throws Exception {
        List<String> snapshot = openApiPathsSnapshot();

        assertThat(snapshot)
            .as("OpenAPI paths snapshot should stay sorted so operation diffs remain reviewable")
            .containsExactlyElementsOf(snapshot.stream().sorted().distinct().toList());
    }

    private List<String> openApiPaths() {
        return controllers().stream()
            .flatMap(controller -> ControllerEndpointCatalog.endpoints(controller).stream())
            .map(endpoint -> endpoint.httpMethod() + " " + endpoint.path()
                + " operationId=" + ControllerEndpointCatalog.endpointId(endpoint.controller(), endpoint.method())
                + " pathParams=" + ControllerEndpointCatalog.pathTemplateVariableNames(endpoint.path())
                + " queryParams=" + ControllerEndpointCatalog.requestParamNames(endpoint.method())
                + " requestBody=" + ControllerEndpointCatalog.requestBodyType(endpoint.method())
                + " requestBodyRequired=" + ControllerEndpointCatalog.requestBodyRequired(endpoint.method())
                + " responseEnvelope=" + endpoint.method().getReturnType().getSimpleName()
                + " responseData=" + OpenApiContractDocument.responseDataTypeName(endpoint.method()))
            .sorted()
            .toList();
    }

    private List<String> openApiPathsSnapshot() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/contracts/openapi-paths.snapshot")).stream()
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

    private Map<String, Object> response(Map<String, Object> paths, String path, String method) {
        return map(operation(paths, path, method).get("responses")).entrySet().stream()
            .filter(entry -> "200".equals(entry.getKey()))
            .map(Map.Entry::getValue)
            .map(this::map)
            .findFirst()
            .orElseThrow();
    }

    private Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
        return map(map(paths.get(path)).get(method));
    }

    private Map<String, Object> parameterSchema(Map<String, Object> operation, String name) {
        return list(operation.get("parameters")).stream()
            .map(this::map)
            .filter(parameter -> name.equals(parameter.get("name")))
            .findFirst()
            .map(parameter -> map(parameter.get("schema")))
            .orElseThrow();
    }

    private Map<String, Object> dataSchema(Map<String, Object> response) {
        Map<String, Object> schema = map(map(map(response.get("content")).get("application/json")).get("schema"));
        return map(map(schema.get("properties")).get("data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
