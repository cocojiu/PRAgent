package com.repoguard.agent.preflight;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.review.*;
import com.repoguard.agent.security.PreflightCredentialService;
import com.repoguard.agent.security.PreflightCredentialService.Grant;
import com.repoguard.agent.tenancy.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreflightPreviewServiceTest {
    private final ObjectMapper json = new JacksonConfig().objectMapper();
    private final RepositoryPolicyRuntime policies = mock(RepositoryPolicyRuntime.class);
    private final RepositoryPolicyEvaluationService evaluator = new RepositoryPolicyEvaluationService(new ServerRiskAggregator());
    private final RuleBasedPullRequestReviewer rules = mock(RuleBasedPullRequestReviewer.class);
    private final LlmPullRequestReviewer llm = mock(LlmPullRequestReviewer.class);
    private final PreflightPreviewStore store = mock(PreflightPreviewStore.class);
    private final PreflightCredentialService credentials = mock(PreflightCredentialService.class);
    private final TenantProperties tenants = new TenantProperties();
    private final TenantRepositoryResolver repositories = mock(TenantRepositoryResolver.class);
    private final GithubIntegrationProvider integrations = mock(GithubIntegrationProvider.class);
    private final com.repoguard.agent.review.ReviewRuleProvider ruleProvider = mock(com.repoguard.agent.review.ReviewRuleProvider.class);
    private PreflightPreviewService service;
    private RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation policy;
    private final Grant grant = new Grant(7L, 3, 1, "fixture/repo", true, Long.MAX_VALUE, UUID.randomUUID().toString());

    @BeforeEach void setup() {
        service = new PreflightPreviewService(policies, evaluator, rules, llm, store, credentials,
            tenants, repositories, integrations, json, true, ruleProvider);
        var settings = new ReviewPolicySettings(true, true, "fixture", "fixture-model", "https://fixture.invalid",
            UUID.randomUUID().toString(), 10, BigDecimal.ZERO, 512, false, 1, 20, 1000, 10, 500,
            BigDecimal.ONE, BigDecimal.ONE);
        var configured = new ReviewRuleSettings("FIXED_SLEEP", "ENABLED", "*", "MEDIUM", 80,
            EnforcementMode.OBSERVE, "", "");
        when(ruleProvider.getRulesById()).thenReturn(Map.of(configured.id(), configured));
        policy = evaluator.evaluate(settings, Map.of(configured.id(), configured), null, null, List.of(), List.of());
        when(policies.preview("fixture", "repo", null)).thenReturn(policy);
        when(integrations.getSettings()).thenReturn(new GithubIntegrationSettings("GITHUB", "CONFIGURED", null,
            null, null, "fixture", "repo", 1L));
        when(rules.review(any(), any(), any())).thenReturn(ReviewResult.completed("LOW", List.of()));
    }

    private Map<String, Object> payload(boolean model) {
        return new LinkedHashMap<>(Map.of("repository", "fixture/repo", "baseSha", "a".repeat(40),
            "mergeBaseSha", "a".repeat(40), "headSha", "b".repeat(40), "llmRequested", model,
            "files", List.of(Map.of("filename", "src/Example.java", "status", "modified",
                "patch", "@@ -1 +1 @@\n-old\n+Thread.sleep(1);\n"))));
    }
    private JsonNode call(Map<String, Object> value) throws Exception {
        byte[] body = json.writeValueAsBytes(value);
        return json.valueToTree(service.preview(grant, body, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))));
    }

    @Test void rulesOnlyPreviewUsesServerBasePolicyAndRestoresTenantContext() throws Exception {
        try (TenantContext.Scope scope = TenantContext.withTenant(99L)) {
            assertThat(scope).isNotNull();
            var response = call(payload(false));
            assertThat(response.path("productionPolicyVerified").asBoolean()).isTrue();
            assertThat(response.path("repository").asText()).isEqualTo("fixture/repo");
            assertThat(response.path("policyFingerprint").asText()).matches("[a-f0-9]{64}");
            assertThat(response.path("llmExecuted").asBoolean()).isFalse();
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(99);
        }
        var diff = org.mockito.ArgumentCaptor.forClass(PullRequestDiff.class);
        verify(rules).review(diff.capture(), any(), any());
        assertThat(diff.getValue().headSha()).isEqualTo("b".repeat(40));
        assertThat(diff.getValue().files().getFirst().additions()).isEqualTo(1);
        assertThat(diff.getValue().files().getFirst().deletions()).isEqualTo(1);
        verifyNoInteractions(llm); verify(store).complete(eq(grant), anyString(), anyString());
    }

    @Test void malformedJsonAndDigestMismatchDoNotClaimOrExecute() {
        assertThatThrownBy(() -> service.preview(grant, "{".getBytes(StandardCharsets.UTF_8),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("{".getBytes(StandardCharsets.UTF_8)))))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.preview(grant, new byte[0], "wrong")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, rules, llm);
    }

    @Test void scopeEscapesContextAndMissingCommitAreRejectedBeforeAdmission() throws Exception {
        var foreign = payload(false); foreign.put("repository", "foreign/repo");
        assertThatThrownBy(() -> call(foreign)).isInstanceOf(BusinessException.class);
        var missing = payload(false); missing.remove("headSha");
        assertThatThrownBy(() -> call(missing)).isInstanceOf(BusinessException.class);
        for (String path : List.of("../secret", "/etc/passwd", "a\\b", "a:b", "a//b")) {
            var invalid = payload(false);
            invalid.put("files", List.of(Map.of("filename", path, "status", "modified", "patch", "+x")));
            assertThatThrownBy(() -> call(invalid)).isInstanceOf(BusinessException.class);
        }
        var context = payload(false); context.put("context", "workspace");
        assertThatThrownBy(() -> call(context)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, rules, llm);
    }

    @Test void crossTenantRepositoryAndPersonalUnconfiguredRepositoryAreRejected() {
        tenants.setEnabled(true);
        when(repositories.resolve("fixture", "repo", null)).thenReturn(new TenantRepositoryBinding(2L, "other", "fixture", "repo", null));
        assertThatThrownBy(() -> call(payload(false))).isInstanceOf(BusinessException.class);
        tenants.setEnabled(false);
        when(integrations.getSettings()).thenReturn(GithubIntegrationSettings.empty());
        assertThatThrownBy(() -> call(payload(false))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, rules, llm);
    }

    @Test void cachedResultDoesNotRepeatPolicyReadRuleScanOrProvider() throws Exception {
        when(store.claim(eq(grant), anyString(), eq(false))).thenReturn("{\"scope\":\"ONLINE_PREFLIGHT\"}");
        assertThat(call(payload(false)).path("scope").asText()).isEqualTo("ONLINE_PREFLIGHT");
        verifyNoInteractions(policies, rules, llm);
        verify(store, never()).complete(any(), anyString(), anyString());
    }

    @Test void unavailablePolicyMarksClaimFailedWithoutFallbackToOfflineDefaults() {
        var unavailable = evaluator.evaluate(policy.effectiveSettings(), Map.of(), null, null, List.of(), List.of("unavailable"));
        when(policies.preview("fixture", "repo", null)).thenReturn(unavailable);
        assertThatThrownBy(() -> call(payload(false))).isInstanceOf(BusinessException.class);
        verify(store).fail(eq(grant), anyString()); verifyNoInteractions(rules, llm);
    }

    @Test void modelNeedsExplicitServerAndCredentialOptIn() {
        service = new PreflightPreviewService(policies, evaluator, rules, llm, store, credentials,
            tenants, repositories, integrations, json, false, ruleProvider);
        assertThatThrownBy(() -> call(payload(true))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, llm);
    }

    @Test void modelReusesConfiguredPipelineWithHardSharedBudgetAndNoFormalTaskId() throws Exception {
        when(llm.reviewForPreflight(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            var task = invocation.getArgument(0, com.repoguard.agent.entity.ReviewTask.class);
            assertThat(task.getId()).isNull(); assertThat(task.getPrNumber()).isZero();
            var budget = invocation.getArgument(4, LlmEvaluationBudget.class);
            try (var reservation = budget.reserve("fixture", "fixture", 512, BigDecimal.ONE, BigDecimal.ONE)) {
                assertThat(reservation).isNotNull();
                assertThat(budget.accountedCost()).isLessThanOrEqualTo(new BigDecimal("0.10"));
            }
            return ReviewResult.completed("LOW", List.of());
        });
        assertThat(call(payload(true)).path("llmExecuted").asBoolean()).isTrue();
        verify(llm).reviewForPreflight(any(), any(), any(), eq(policy.effectiveSettings()), any());
        verifyNoInteractions(rules);
    }

    @Test void incompleteRuleScanAndFallbackAreNotReportedAsComplete() throws Exception {
        when(rules.review(any(), any(), any())).thenReturn(ReviewResult.completed("LOW", List.of())
            .withIncompleteInput("execution_budget_exceeded:rule_scan", "partial"));
        assertThat(call(payload(false)).path("scanComplete").asBoolean()).isFalse();
        when(rules.review(any(), any(), any())).thenReturn(ReviewResult.fallback("LOW", "provider_failed", List.of()));
        assertThat(call(payload(false)).path("scanComplete").asBoolean()).isFalse();
    }

    @Test void ruleChangesDuringExecutionFailClosedAndDetectorVersionsChangeFingerprint() throws Exception {
        var first = call(payload(false)).path("policyFingerprint").asText();
        var changed = new ReviewRuleSettings("FIXED_SLEEP", "ENABLED", "*", "MEDIUM", 80,
            EnforcementMode.OBSERVE, "", "", "", "detector-v-next", 2, 2);
        when(ruleProvider.getRulesById()).thenReturn(Map.of(changed.id(), changed));
        assertThat(call(payload(false)).path("policyFingerprint").asText()).isNotEqualTo(first);
        when(ruleProvider.getRulesById()).thenReturn(Map.of(changed.id(), changed)).thenReturn(Map.of());
        assertThatThrownBy(() -> call(payload(false))).isInstanceOf(BusinessException.class);
        verify(store).fail(eq(grant), anyString());
    }

    @Test void expiredPolicyReadDoesNotStartModelCall() {
        when(policies.preview("fixture", "repo", null)).thenAnswer(_ -> {
            Thread.sleep(30_100);
            return policy;
        });
        assertThatThrownBy(() -> call(payload(true)))
            .isInstanceOf(com.repoguard.agent.review.ReviewBudgetExceededException.class);
        verifyNoInteractions(rules, llm);
        verify(store).fail(eq(grant), anyString());
    }

    @Test void responseProjectionMasksCredentialsAndDropsEvidence() throws Exception {
        var finding = new ReviewFindingResult("MEDIUM", "RULE", "FIXED_SLEEP", "src/Example.java", 1,
            "password='private-fixture'", "", "80", "raw private-fixture", "", "", false,
            "", "OBSERVE", "", "", "", List.of(), false, "NOT_REQUIRED");
        when(rules.review(any(), any(), any())).thenReturn(ReviewResult.completed("MEDIUM", List.of(finding)));
        var result = call(payload(false));
        assertThat(result.toString()).doesNotContain("private-fixture", "raw", "apiKey");
        assertThat(result.path("findings").get(0).path("fingerprint").asText()).matches("[a-f0-9]{64}");
    }
}
