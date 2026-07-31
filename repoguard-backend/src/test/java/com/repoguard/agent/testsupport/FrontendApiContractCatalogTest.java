package com.repoguard.agent.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontendApiContractCatalogTest {

    @Test
    void parsesDynamicPathsAndInlineQueryShapesFromTypedContracts() throws Exception {
        Map<String, FrontendApiContractCatalog.EndpointContract> contracts =
            FrontendApiContractCatalog.endpointContracts();

        FrontendApiContractCatalog.EndpointContract findings = contracts.get("fetchReviewFindings");
        assertThat(findings.endpointKey()).isEqualTo("GET /api/v1/reviews/{id}/findings");
        assertThat(findings.queryParamNames())
            .containsExactly("page", "pageSize", "severity", "category", "feedbackStatus");
        assertThat(findings.hasRequestBody()).isFalse();
        assertThat(findings.requestBodyRequired()).isFalse();

        assertThat(contracts.get("rollbackReviewRule").endpointKey()).isEqualTo(
            "POST /api/v1/config/review-rules/{id}/versions/{policyVersion}/rollback"
        );
        assertThat(contracts.get("rollbackReviewStrategy").endpointKey()).isEqualTo(
            "POST /api/v1/config/review-strategy/versions/{snapshotId}/rollback"
        );
        assertThat(findings.responseType()).isEqualTo("PageResponse<ReviewFinding>");

        FrontendApiContractCatalog.EndpointContract feedback = contracts.get("updateFindingFeedback");
        assertThat(feedback.endpointKey()).isEqualTo("POST /api/v1/reviews/{id}/findings/{findingId}/feedback");
        assertThat(feedback.hasRequestBody()).isTrue();
        assertThat(feedback.requestBodyRequired()).isTrue();
    }

    @Test
    void parsesSharedNotificationQueryHelper() throws Exception {
        Map<String, FrontendApiContractCatalog.EndpointContract> contracts =
            FrontendApiContractCatalog.endpointContracts();

        assertThat(contracts.get("fetchNotificationEvents").queryParamNames())
            .containsExactly("page", "pageSize", "status", "taskId");
        assertThat(contracts.get("fetchNotificationDeliveries").queryParamNames())
            .containsExactly("page", "pageSize", "status", "taskId");
    }

    @Test
    void normalizesBackendAndFrontendResponseTypesThroughSharedAliases() {
        assertThat(FrontendApiContractCatalog.normalizeJavaResponseType("PageResponse<ReviewTaskListItem>"))
            .isEqualTo("PageResponse<ReviewTask>");
        assertThat(FrontendApiContractCatalog.normalizeJavaResponseType("List<DashboardMetricDto>"))
            .isEqualTo("DashboardMetric[]");
        assertThat(FrontendApiContractCatalog.normalizeFrontendResponseType("Required<ChartSlice>[]"))
            .isEqualTo("ChartSlice[]");
        assertThat(FrontendApiContractCatalog.normalizeJavaResponseType("Void")).isEqualTo("void");
    }

    @Test
    void productionFrontendApiPathLiteralsStayBehindTypedContracts() throws Exception {
        assertThat(FrontendApiContractCatalog.apiPathLiteralsOutsideClientAndContracts()).isEmpty();
    }
}
