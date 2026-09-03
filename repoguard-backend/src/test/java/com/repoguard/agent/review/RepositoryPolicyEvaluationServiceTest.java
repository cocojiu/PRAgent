package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryPolicyEvaluationServiceTest {

    private final RepositoryPolicyEvaluationService service =
        new RepositoryPolicyEvaluationService(new ServerRiskAggregator());

    @Test
    void platformFloorPreventsBasePolicyFromDisablingAHighRiskBlockRule() {
        ReviewRuleSettings adminRule = new ReviewRuleSettings(
            "RG-AUTH-001", "ENABLED", "", "HIGH", 90, EnforcementMode.BLOCK, "", "", ""
        );
        RepositoryPolicyDocument base = new RepositoryPolicyDocument(
            1, java.util.List.of(), java.util.List.of(),
            Map.of("RG-AUTH-001", new RepositoryPolicyDocument.RuleOverride(false, "LOW", EnforcementMode.OBSERVE)),
            null, null, java.util.List.of()
        );

        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            ReviewPolicySettings.empty(),
            Map.of("RG-AUTH-001", adminRule),
            base,
            RepositoryPolicyDocument.empty(),
            java.util.List.of(),
            java.util.List.of()
        );

        RepositoryPolicyEvaluationService.RuleDecision decision = evaluation.rules().get("RG-AUTH-001");
        assertThat(decision.effectiveEnabled()).isTrue();
        assertThat(decision.effectiveSeverity()).isEqualTo("HIGH");
        assertThat(decision.effectiveEnforcement()).isEqualTo(EnforcementMode.BLOCK);
        assertThat(evaluation.warnings()).anyMatch(value -> value.contains("platform_floor"));
    }

    @Test
    void headPolicyIsExposedForPreviewButDoesNotChangeEffectiveBaseSettings() {
        ReviewPolicySettings server = new ReviewPolicySettings(
            true, true, "openai", "model", "https://llm.invalid", "secret", 30, null, 8000,
            true, 1, 10, 100, 10, 1000, null, null
        );
        RepositoryPolicyDocument head = new RepositoryPolicyDocument(
            1, java.util.List.of(), java.util.List.of(), Map.of(),
            new RepositoryPolicyDocument.LlmOverride(false, 1, null), null, java.util.List.of()
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            server, Map.of(), RepositoryPolicyDocument.empty(), head, java.util.List.of(), java.util.List.of()
        );

        assertThat(evaluation.headPolicy().llm().enabled()).isFalse();
        assertThat(evaluation.effectiveSettings().enabled()).isTrue();
        assertThat(evaluation.effectiveSettings().maxTokens()).isEqualTo(8000);
    }

    @Test
    void repositoryIncludeAndExcludePatternsLimitFindingsBeforeAggregation() {
        RepositoryPolicyDocument base = new RepositoryPolicyDocument(
            1,
            List.of("src/**"),
            List.of("src/generated/**"),
            Map.of(),
            null,
            null,
            List.of()
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            ReviewPolicySettings.empty(), Map.of(), base, RepositoryPolicyDocument.empty(), List.of(), List.of()
        );
        ReviewResult result = ReviewResult.completed("HIGH", List.of(
            new ReviewFindingResult("HIGH", "RULE", "RG-ONE", "src/App.java", 1, "keep", "fix"),
            new ReviewFindingResult("HIGH", "RULE", "RG-TWO", "src/generated/App.java", 1, "drop", "fix"),
            new ReviewFindingResult("HIGH", "RULE", "RG-THREE", "docs/App.md", 1, "drop", "fix")
        ));

        ReviewResult adjusted = service.applyFindings(result, evaluation);

        assertThat(adjusted.findings()).extracting(ReviewFindingResult::filePath)
            .containsExactly("src/App.java");
    }

    @Test
    void costBudgetMarksAnOverBudgetReviewAsIncomplete() {
        RepositoryPolicyDocument base = new RepositoryPolicyDocument(
            1, List.of(), List.of(), Map.of(),
            new RepositoryPolicyDocument.LlmOverride(true, null, new BigDecimal("0.10")),
            null, List.of()
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            ReviewPolicySettings.empty(), Map.of(), base, RepositoryPolicyDocument.empty(), List.of(), List.of()
        );
        ReviewResult result = new ReviewResult(
            "LOW", "COMPLETED", null, List.of(), "provider", "model", 5, "COMPLETED", null,
            1, 1, 2, new BigDecimal("0.11")
        );

        ReviewResult adjusted = service.applyFindings(result, evaluation);

        assertThat(adjusted.statusDetail()).contains("repository_policy_cost_budget_exceeded");
        assertThat(adjusted.llmPromptSummary()).contains("repositoryPolicyCostBudgetExceeded=true");
    }

    @Test
    void appliesRuleOverridesAndSuppressesOnlyMatchingUnexpiredFindings() {
        ReviewRuleSettings rule = new ReviewRuleSettings(
            "RG-AUTH-001", "ENABLED", "", "MEDIUM", 90, EnforcementMode.COMMENT, "", "", ""
        );
        RepositoryPolicyDocument.SuppressionReference suppression = new RepositoryPolicyDocument.SuppressionReference(
            "RG-AUTH-001", "src/Auth.java", "secret", "accepted fixture", OffsetDateTime.now().plusDays(1)
        );
        RepositoryPolicyDocument base = new RepositoryPolicyDocument(
            1, List.of(), List.of(),
            Map.of("RG-AUTH-001", new RepositoryPolicyDocument.RuleOverride(true, "HIGH", EnforcementMode.BLOCK)),
            null, null, List.of(suppression)
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            ReviewPolicySettings.empty(), Map.of("RG-AUTH-001", rule), base,
            RepositoryPolicyDocument.empty(), List.of(), List.of()
        );
        ReviewResult result = ReviewResult.completed("MEDIUM", List.of(
            new ReviewFindingResult("MEDIUM", "RULE", "RG-AUTH-001", "src/Auth.java", 1, "secret found", "fix"),
            new ReviewFindingResult("MEDIUM", "RULE", "RG-AUTH-001", "src/Other.java", 2, "other", "fix")
        ));

        ReviewResult adjusted = service.applyFindings(result, evaluation);

        assertThat(adjusted.findings()).hasSize(1);
        assertThat(adjusted.findings().getFirst().severity()).isEqualTo("HIGH");
        assertThat(adjusted.findings().getFirst().enforcementMode()).isEqualTo("BLOCK");
        assertThat(adjusted.findings().getFirst().isBlocking()).isFalse();
    }

    @Test
    void handlesLlmFloorsUnknownRulesAndNullInputs() {
        ReviewPolicySettings server = new ReviewPolicySettings(
            true, false, "openai", "model", "https://llm.invalid", "secret", 30, null, 1000,
            true, 1, 10, 100, 10, 1000, null, null
        );
        RepositoryPolicyDocument base = new RepositoryPolicyDocument(
            1, List.of(), List.of(),
            Map.of("RG-UNKNOWN", new RepositoryPolicyDocument.RuleOverride(false, "LOW", EnforcementMode.OBSERVE)),
            new RepositoryPolicyDocument.LlmOverride(true, 200000, null), null, List.of()
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            server, Map.of(), base, null, null, List.of("source-warning")
        );
        assertThat(evaluation.effectiveSettings().enabled()).isFalse();
        assertThat(evaluation.effectiveSettings().maxTokens()).isEqualTo(128000);
        assertThat(evaluation.warnings()).contains("source-warning", "RG-UNKNOWN:unknown_rule_ignored");
        assertThat(service.applyLlmSettings(server, null)).isEqualTo(server);
        assertThat(service.applyFindings(null, evaluation)).isNull();
    }

    @Test
    void supportsSymbolSuppressionAndExpiresOldReferences() {
        ReviewRuleSettings rule = new ReviewRuleSettings(
            "RG-AUTH-001", "ENABLED", "", "HIGH", 90, EnforcementMode.BLOCK, "", "", ""
        );
        List<RepositoryPolicyDocument.SuppressionReference> suppressions = List.of(
            new RepositoryPolicyDocument.SuppressionReference(
                "RG-AUTH-001", null, "legacy", "reason", OffsetDateTime.now().plusDays(1)
            ),
            new RepositoryPolicyDocument.SuppressionReference(
                "RG-AUTH-001", null, "old", "reason", OffsetDateTime.now().minusDays(1)
            )
        );
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = service.evaluate(
            ReviewPolicySettings.empty(), Map.of("RG-AUTH-001", rule),
            RepositoryPolicyDocument.empty(), null, suppressions, List.of()
        );
        ReviewResult result = ReviewResult.completed("HIGH", List.of(
            new ReviewFindingResult("HIGH", "RULE", "RG-AUTH-001", "src/Auth.java", 1, "legacy path", "fix"),
            new ReviewFindingResult("HIGH", "RULE", "RG-AUTH-001", "src/Auth.java", 2, "old path", "fix")
        ));
        assertThat(service.applyFindings(result, evaluation).findings()).hasSize(1);
    }
}
