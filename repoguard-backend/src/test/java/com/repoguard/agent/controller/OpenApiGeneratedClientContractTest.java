package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.FrontendApiContractCatalog;
import com.repoguard.agent.testsupport.OpenApiContractDocument;
import com.repoguard.agent.testsupport.OpenApiGeneratedClientDryRun;
import com.repoguard.agent.testsupport.OpenApiGeneratedClientDryRun.GeneratedOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenApiGeneratedClientContractTest {

    private static final Path GENERATED_CLIENT_SURFACE_SNAPSHOT =
        Path.of("src/test/resources/contracts/openapi-generated-client.surface.snapshot");
    private static final Path GENERATED_CLIENT_MIGRATION_SNAPSHOT =
        Path.of("src/test/resources/contracts/openapi-generated-client.migration.snapshot");
    private static final Path GENERATED_CLIENT_FRONTEND_SIGNATURE_SNAPSHOT =
        Path.of("src/test/resources/contracts/openapi-generated-client.frontend-signature.snapshot");

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

    @Test
    void generatedClientDryRunProducesCodegenFriendlyTypescriptSurface() throws Exception {
        List<String> surface = OpenApiGeneratedClientDryRun.clientSurfaceLines(openApiDocument());

        assertThat(surface)
            .as("Generated client dry-run surface should stay sorted and unique for reviewable diffs")
            .isNotEmpty()
            .containsExactlyElementsOf(surface.stream().sorted().distinct().toList())
            .allMatch(line -> line.matches("[a-zA-Z][a-zA-Z0-9]*\\(.*\\): Promise<[^>]+(>[^>]*)?>; // (GET|POST|PUT|DELETE) /api/v1/.*"))
            .noneMatch(line -> line.contains("unknown"))
            .noneMatch(line -> line.contains("byte[]"));
        assertThat(surface)
            .contains(
                "authControllerLogout(input?: { body?: AuthLogoutRequest }): Promise<void>; // POST /api/v1/auth/logout",
                "reviewControllerListReviews(input?: { query?: { cursor?: string; keyword?: string; page?: number; pageSize?: number; repository?: string; riskLevel?: string; source?: string; status?: string; triggerSource?: string } }): Promise<PageResponse<ReviewTask>>; // GET /api/v1/reviews",
                "reviewControllerListChangedFiles(input: { path: { id: number }; query?: { hasFinding?: boolean; page?: number; pageSize?: number } }): Promise<PageResponse<ChangedFile>>; // GET /api/v1/reviews/{id}/changed-files",
                "reviewControllerUpdateFindingFeedback(input: { path: { findingId: number; id: number }; body: FindingFeedbackRequest }): Promise<FindingFeedbackResponse>; // POST /api/v1/reviews/{id}/findings/{findingId}/feedback"
            );
    }

    @Test
    void generatedClientDryRunSurfaceSnapshotStaysReviewed() throws Exception {
        assertThat(OpenApiGeneratedClientDryRun.clientSurfaceLines(openApiDocument()))
            .as("Generated client dry-run surface changed. Review the TypeScript client impact before updating the snapshot.")
            .containsExactlyElementsOf(generatedClientSurfaceSnapshot());
    }

    @Test
    void frontendTypedOperationsMapToGeneratedClientOperationsSnapshotStaysReviewed() throws Exception {
        assertThat(generatedClientMigrationLines())
            .as("Frontend typed operation to generated client operation mapping changed. Review migration impact before updating the snapshot.")
            .containsExactlyElementsOf(generatedClientMigrationSnapshot());
    }

    @Test
    void frontendTypedOperationsMapToGeneratedClientSignaturesSnapshotStaysReviewed() throws Exception {
        assertThat(generatedClientFrontendSignatureLines())
            .as("Frontend typed operation to generated client signature mapping changed. Review wrapper migration impact before updating the snapshot.")
            .containsExactlyElementsOf(generatedClientFrontendSignatureSnapshot());
    }

    private Map<String, GeneratedOperation> generatedOperations() throws Exception {
        return OpenApiGeneratedClientDryRun.operations(openApiDocument());
    }

    private Map<String, Object> openApiDocument() throws Exception {
        return OpenApiContractDocument.fromJson(Files.readString(Path.of("src/test/resources/contracts/openapi.json")));
    }

    private List<String> generatedClientSurfaceSnapshot() throws Exception {
        return Files.readAllLines(GENERATED_CLIENT_SURFACE_SNAPSHOT).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }

    private List<String> generatedClientMigrationLines() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();
        return FrontendApiContractCatalog.endpointContracts().entrySet().stream()
            .map(entry -> {
                GeneratedOperation generatedOperation = generatedOperations.get(entry.getValue().endpointKey());
                assertThat(generatedOperation)
                    .as(entry.getKey() + " must map to a generated OpenAPI client operation")
                    .isNotNull();
                return entry.getKey() + " -> " + generatedOperation.operationId() + " // " + entry.getValue().endpointKey();
            })
            .sorted()
            .toList();
    }

    private List<String> generatedClientFrontendSignatureLines() throws Exception {
        Map<String, GeneratedOperation> generatedOperations = generatedOperations();
        return FrontendApiContractCatalog.endpointContracts().entrySet().stream()
            .map(entry -> {
                GeneratedOperation generatedOperation = generatedOperations.get(entry.getValue().endpointKey());
                assertThat(generatedOperation)
                    .as(entry.getKey() + " must map to a generated OpenAPI client operation")
                    .isNotNull();
                return entry.getKey() + " -> " + generatedOperation.clientSurfaceLine();
            })
            .sorted()
            .toList();
    }

    private List<String> generatedClientMigrationSnapshot() throws Exception {
        return Files.readAllLines(GENERATED_CLIENT_MIGRATION_SNAPSHOT).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }

    private List<String> generatedClientFrontendSignatureSnapshot() throws Exception {
        return Files.readAllLines(GENERATED_CLIENT_FRONTEND_SIGNATURE_SNAPSHOT).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }

}
