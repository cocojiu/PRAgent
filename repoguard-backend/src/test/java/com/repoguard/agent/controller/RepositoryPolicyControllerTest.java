package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.RepositoryPolicyPreviewResponse;
import com.repoguard.agent.dto.RepositorySuppressionRequest;
import com.repoguard.agent.dto.RepositorySuppressionResponse;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.RepositoryPolicyDocument;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import com.repoguard.agent.review.RepositoryPolicyRuntime;
import com.repoguard.agent.review.RepositorySuppressionService;
import com.repoguard.agent.review.ReviewPolicySettings;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryPolicyControllerTest {

    private final RepositoryPolicyRuntime runtime = mock(RepositoryPolicyRuntime.class);
    private final RepositorySuppressionService suppressionService = mock(RepositorySuppressionService.class);
    private final RepositoryPolicyController controller = new RepositoryPolicyController(runtime, suppressionService);

    @Test
    void previewsPolicyAndListsSuppressions() {
        RepositoryPolicyEvaluationService.RuleDecision decision = new RepositoryPolicyEvaluationService.RuleDecision(
            "RG-AUTH-001", true, null, true, "HIGH", null, "HIGH", EnforcementMode.BLOCK, null,
            EnforcementMode.BLOCK, null
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = new RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation(
            RepositoryPolicyDocument.empty(), RepositoryPolicyDocument.empty(), Map.of("RG-AUTH-001", decision),
            ReviewPolicySettings.empty(), null, "SUMMARY", "NEUTRAL", List.of(), List.of("warning")
        );
        when(runtime.preview("octocat", "repo", "head")).thenReturn(evaluation);
        RepositorySuppressionResponse suppression = response("ACTIVE");
        when(suppressionService.list("octocat", "repo", 25)).thenReturn(List.of(suppression));

        ApiResponse<RepositoryPolicyPreviewResponse> preview = controller.preview("octocat", "repo", "head");
        ApiResponse<List<RepositorySuppressionResponse>> list = controller.listSuppressions("octocat", "repo", 25);

        assertThat(preview.success()).isTrue();
        assertThat(preview.data().rules()).containsKey("RG-AUTH-001");
        assertThat(preview.data().warnings()).containsExactly("warning");
        assertThat(list.data()).containsExactly(suppression);
    }

    @Test
    void createsActivatesAndRevokesSuppressionUsingAuthenticatedUsername() {
        HttpServletRequest request = authenticatedRequest();
        RepositorySuppressionRequest payload = new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", "src/**", null, "known fixture",
            OffsetDateTime.now().plusDays(10).toString()
        );
        RepositorySuppressionResponse proposed = response("PROPOSED");
        RepositorySuppressionResponse active = response("ACTIVE");
        RepositorySuppressionResponse revoked = response("REVOKED");
        when(suppressionService.create(payload, "alice")).thenReturn(proposed);
        when(suppressionService.activate(9L, "alice", "approved")).thenReturn(active);
        when(suppressionService.revoke(9L, "alice", "fixed")).thenReturn(revoked);

        assertThat(controller.createSuppression(request, payload).data()).isEqualTo(proposed);
        assertThat(controller.activate(request, 9L, "approved").data()).isEqualTo(active);
        assertThat(controller.revoke(request, 9L, "fixed").data()).isEqualTo(revoked);
    }

    @Test
    void requiresAuthenticationForMutation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RepositorySuppressionRequest payload = new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", "src/**", null, "reason",
            OffsetDateTime.now().plusDays(10).toString()
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.createSuppression(request, payload))
            .hasMessageContaining("Authentication token is required");
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
            new AuthenticatedPrincipal(1L, "alice", "ADMIN", Long.MAX_VALUE, 1));
        return request;
    }

    private RepositorySuppressionResponse response(String status) {
        return new RepositorySuppressionResponse(
            9L, "octocat", "repo", "RG-AUTH-001", "src/**", null, "reason", status,
            "alice", "2099-01-01T00:00:00", 1, 0, "2026-01-01T00:00:00", "2026-01-01T00:00:00"
        );
    }
}
