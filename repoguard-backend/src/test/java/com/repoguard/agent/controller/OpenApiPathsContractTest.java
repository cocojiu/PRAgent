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
                + " responseEnvelope=" + endpoint.method().getReturnType().getSimpleName())
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
}
