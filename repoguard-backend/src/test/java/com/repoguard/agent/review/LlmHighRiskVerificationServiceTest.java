package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.LlmVerificationProperties;
import com.repoguard.agent.entity.ReviewTask;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class LlmHighRiskVerificationServiceTest {

    @Test
    void verifiedCandidateRemainsCommentOnlyAndCannotSelfBlockByDefault() {
        StubCaller caller = new StubCaller(true, result(verifiedJson(), 30, 10, 40));
        LlmHighRiskVerificationOutcome outcome = service(new LlmVerificationProperties()).verify(
            context(caller),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            activeBudget()
        );

        ReviewFindingResult finding = outcome.review().findings().getFirst();
        assertThat(finding.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.enforcementMode()).isEqualTo("COMMENT");
        assertThat(finding.blockingCandidate()).isTrue();
        assertThat(finding.isBlocking()).isFalse();
        assertThat(outcome.review().riskLevel()).isEqualTo("MEDIUM");
        assertThat(outcome.verificationUsage()).isEqualTo(result(verifiedJson(), 30, 10, 40));
        assertThat(outcome.summary()).isEqualTo(new LlmVerificationSummary(1, 1, 0, 0));
        assertThat(caller.verificationCalls).isEqualTo(1);
    }

    @Test
    void serverBlockModeRequiresAllVerifiedHighConfidenceGates() {
        LlmVerificationProperties properties = new LlmVerificationProperties();
        properties.setEnforcementMode("BLOCK");
        StubCaller caller = new StubCaller(true, result(verifiedJson(), 1, 1, 2));

        LlmHighRiskVerificationOutcome outcome = service(properties).verify(
            context(caller),
            diff(),
            ReviewResult.completed("CRITICAL", List.of(candidate("CRITICAL"))),
            activeBudget()
        );

        ReviewFindingResult finding = outcome.review().findings().getFirst();
        assertThat(finding.isBlocking()).isTrue();
        assertThat(finding.enforcementMode()).isEqualTo("BLOCK");
        assertThat(finding.policyReason()).isEqualTo("llm_verified_server_block_policy_satisfied");
        assertThat(outcome.review().riskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    void observeStrategySnapshotCapsADeploymentConfiguredForBlock() {
        LlmVerificationProperties properties = new LlmVerificationProperties();
        properties.setEnforcementMode("BLOCK");
        StubCaller caller = new StubCaller(true, result(verifiedJson(), 1, 1, 2));
        ReviewStrategyRelease observeRelease = new ReviewStrategyRelease(
            17,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            EnforcementMode.OBSERVE,
            true
        );

        LlmHighRiskVerificationOutcome outcome = service(properties).verify(
            context(caller, settings(observeRelease)),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            activeBudget()
        );

        ReviewFindingResult finding = outcome.review().findings().getFirst();
        assertThat(finding.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
        assertThat(finding.isBlocking()).isFalse();
    }

    @Test
    void rejectedOrProtectedCandidateDegradesToMediumObserve() {
        StubCaller caller = new StubCaller(
            true,
            result(rejectedJson(), 2, 1, 3),
            result(protectedJson(), 2, 1, 3)
        );

        LlmHighRiskVerificationOutcome outcome = service(new LlmVerificationProperties()).verify(
            context(caller),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"), candidate("CRITICAL"))),
            activeBudget()
        );

        assertThat(outcome.review().findings()).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo("MEDIUM");
            assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
            assertThat(finding.isBlocking()).isFalse();
            assertThat(finding.blockingCandidate()).isFalse();
            assertThat(finding.verificationStatus()).isEqualTo("REJECTED");
        });
        assertThat(outcome.review().riskLevel()).isEqualTo("INFO");
        assertThat(outcome.summary()).isEqualTo(new LlmVerificationSummary(2, 0, 2, 0));
        assertThat(outcome.verificationUsage().totalTokens()).isEqualTo(6);
    }

    @Test
    void malformedOrUnsupportedVerificationDegradesWithoutDiscardingFinding() {
        StubCaller malformed = new StubCaller(true, result("{\"verdict\":\"VERIFIED\"}", 3, 1, 4));
        LlmHighRiskVerificationOutcome malformedOutcome = service(new LlmVerificationProperties()).verify(
            context(malformed),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            activeBudget()
        );

        assertThat(malformedOutcome.review().findings()).hasSize(1);
        assertThat(malformedOutcome.review().findings().getFirst().verificationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(malformedOutcome.review().findings().getFirst().severity()).isEqualTo("MEDIUM");
        assertThat(malformedOutcome.summary()).isEqualTo(new LlmVerificationSummary(1, 0, 0, 1));
        assertThat(malformedOutcome.verificationUsage().totalTokens()).isEqualTo(4);

        StubCaller unsupported = new StubCaller(false);
        LlmHighRiskVerificationOutcome unsupportedOutcome = service(new LlmVerificationProperties()).verify(
            context(unsupported),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            activeBudget()
        );
        assertThat(unsupportedOutcome.review().findings().getFirst().verificationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(unsupportedOutcome.summary()).isEqualTo(new LlmVerificationSummary(0, 0, 0, 1));
        assertThat(unsupported.verificationCalls).isZero();
    }

    @Test
    void disabledBudgetAndCandidateLimitAreFailClosedObservations() {
        LlmVerificationProperties disabledProperties = new LlmVerificationProperties();
        disabledProperties.setEnabled(false);
        StubCaller disabledCaller = new StubCaller(true, result(verifiedJson(), 1, 1, 2));
        LlmHighRiskVerificationOutcome disabled = service(disabledProperties).verify(
            context(disabledCaller),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            activeBudget()
        );
        assertThat(disabled.review().findings().getFirst().verificationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(disabledCaller.verificationCalls).isZero();

        LlmVerificationProperties limitedProperties = new LlmVerificationProperties();
        limitedProperties.setMaxCandidates(1);
        StubCaller limitedCaller = new StubCaller(true, result(verifiedJson(), 1, 1, 2));
        LlmHighRiskVerificationOutcome limited = service(limitedProperties).verify(
            context(limitedCaller),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"), candidate("HIGH"))),
            activeBudget()
        );
        assertThat(limited.review().findings()).extracting(ReviewFindingResult::verificationStatus)
            .containsExactly("VERIFIED", "LIMIT_EXCEEDED");
        assertThat(limitedCaller.verificationCalls).isEqualTo(1);

        StubCaller budgetCaller = new StubCaller(true, result(verifiedJson(), 1, 1, 2));
        LlmHighRiskVerificationOutcome exhausted = service(new LlmVerificationProperties()).verify(
            context(budgetCaller),
            diff(),
            ReviewResult.completed("HIGH", List.of(candidate("HIGH"))),
            ReviewBudget.startingAt(0, Duration.ZERO, () -> 0)
        );
        assertThat(exhausted.review().findings().getFirst().verificationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(budgetCaller.verificationCalls).isZero();
    }

    private LlmHighRiskVerificationService service(LlmVerificationProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new LlmHighRiskVerificationService(
            properties,
            new LlmHighRiskVerificationParser(objectMapper, new LlmReviewJsonExtractor()),
            new FindingPolicyResolver(),
            new ServerRiskAggregator()
        );
    }

    private ReviewPipelineContext context(LlmReviewCaller caller) {
        return context(caller, settings());
    }

    private ReviewPipelineContext context(LlmReviewCaller caller, ReviewPolicySettings settings) {
        return new ReviewPipelineContext(
            new ReviewTask(),
            diff(),
            settings,
            "prompt",
            System.nanoTime(),
            caller,
            LlmReviewContext.legacy()
        );
    }

    private ReviewFindingResult candidate(String severity) {
        return new ReviewFindingResult(
            severity,
            "LLM",
            null,
            "src/AdminController.java",
            12,
            "New administrative route lacks authorization",
            "Require an administrative role",
            "HIGH",
            "The added route invokes the write handler without a role guard",
            "Unauthorized state change",
            "Add @RequireRole(\"ADMIN\")",
            false,
            "SECURITY",
            "COMMENT",
            "llm_candidate_requires_adversarial_verification",
            "MISSING_AUTHORIZATION",
            "An unauthenticated caller can reach the route",
            List.of("src/SecurityConfig.java"),
            true,
            "PENDING"
        );
    }

    private PullRequestDiff diff() {
        return new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            "head-a",
            List.of(new PullRequestChangedFile(
                "src/AdminController.java",
                "modified",
                1,
                0,
                "@@ -11,0 +12,1 @@\n+public void update() {}"
            ))
        );
    }

    private ReviewPolicySettings settings() {
        return new ReviewPolicySettings(
            true,
            true,
            "openai",
            "gpt-test",
            "https://llm.example.test",
            "llm-key",
            30,
            BigDecimal.valueOf(0.2),
            1024,
            true,
            1,
            99,
            700,
            4,
            450,
            BigDecimal.ONE,
            BigDecimal.valueOf(4)
        );
    }

    private ReviewPolicySettings settings(ReviewStrategyRelease release) {
        ReviewPolicySettings base = settings();
        return new ReviewPolicySettings(
            base.exists(),
            base.llmEnabled(),
            base.llmProvider(),
            base.modelName(),
            base.baseUrl(),
            base.apiKey(),
            base.timeoutSeconds(),
            base.temperature(),
            base.maxTokens(),
            base.fallbackToRules(),
            base.workerConcurrency(),
            base.chunkFileThreshold(),
            base.chunkLineThreshold(),
            base.chunkMaxFiles(),
            base.chunkMaxLines(),
            base.inputTokenPricePerMillion(),
            base.outputTokenPricePerMillion(),
            release
        );
    }

    private ReviewBudget activeBudget() {
        return ReviewBudget.startingAt(0, Duration.ofSeconds(1), () -> 0);
    }

    private LlmCallResult result(String content, int prompt, int completion, int total) {
        return new LlmCallResult(content, prompt, completion, total);
    }

    private String verifiedJson() {
        return decisionJson("VERIFIED", true, true, true, false, "none", "HIGH");
    }

    private String rejectedJson() {
        return decisionJson("REJECTED", false, false, true, false, "none", "HIGH");
    }

    private String protectedJson() {
        return decisionJson("VERIFIED", true, true, true, true, "Class-level role guard", "HIGH");
    }

    private String decisionJson(
        String verdict,
        boolean evidenceSupported,
        boolean preconditionsSatisfied,
        boolean addedLineValid,
        boolean protectionPresent,
        String protection,
        String confidence
    ) {
        return """
            {
              "schemaVersion": "high-risk-verifier-v1",
              "verdict": "%s",
              "evidenceSupported": %s,
              "preconditionsSatisfied": %s,
              "addedLineValid": %s,
              "protectionPresent": %s,
              "existingProtection": "%s",
              "confidence": "%s",
              "reason": "adversarial verification result"
            }
            """.formatted(
                verdict,
                evidenceSupported,
                preconditionsSatisfied,
                addedLineValid,
                protectionPresent,
                protection,
                confidence
            );
    }

    private static final class StubCaller implements LlmReviewCaller {

        private final boolean supported;
        private final Queue<LlmCallResult> results;
        private int verificationCalls;

        private StubCaller(boolean supported, LlmCallResult... results) {
            this.supported = supported;
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff) {
            throw new UnsupportedOperationException("Candidate generation is outside this test");
        }

        @Override
        public boolean supportsHighRiskVerification() {
            return supported;
        }

        @Override
        public LlmCallResult verifyHighRisk(
            ReviewPolicySettings settings,
            ReviewTask task,
            PullRequestDiff diff,
            ReviewFindingResult candidate,
            LlmReviewContext context
        ) {
            verificationCalls++;
            return results.remove();
        }
    }
}
