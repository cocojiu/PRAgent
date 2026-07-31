package com.repoguard.agent.review;

import com.repoguard.agent.config.LlmVerificationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class LlmHighRiskVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmHighRiskVerificationService.class);

    private final LlmVerificationProperties properties;
    private final Function<String, LlmHighRiskVerificationDecision> parser;
    private final FindingPolicyResolver policyResolver;
    private final ServerRiskAggregator riskAggregator;

    @Autowired
    LlmHighRiskVerificationService(
        LlmVerificationProperties properties,
        LlmHighRiskVerificationParser parser,
        FindingPolicyResolver policyResolver,
        ServerRiskAggregator riskAggregator
    ) {
        this(properties, Objects.requireNonNull(parser, "parser")::parse, policyResolver, riskAggregator);
    }

    private LlmHighRiskVerificationService(
        LlmVerificationProperties properties,
        Function<String, LlmHighRiskVerificationDecision> parser,
        FindingPolicyResolver policyResolver,
        ServerRiskAggregator riskAggregator
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.riskAggregator = Objects.requireNonNull(riskAggregator, "riskAggregator");
    }

    static LlmHighRiskVerificationService defaults() {
        return new LlmHighRiskVerificationService(
            new LlmVerificationProperties(),
            content -> {
                throw new IllegalStateException("Configured high-risk verification parser is unavailable");
            },
            new FindingPolicyResolver(),
            new ServerRiskAggregator()
        );
    }

    LlmHighRiskVerificationOutcome verify(
        ReviewPipelineContext context,
        PullRequestDiff diff,
        ReviewResult scoredReview,
        ReviewBudget budget
    ) {
        List<ReviewFindingResult> findings = scoredReview == null || scoredReview.findings() == null
            ? List.of()
            : scoredReview.findings();
        List<ReviewFindingResult> resolved = new ArrayList<>(findings.size());
        LlmCallResult usage = null;
        int attempted = 0;
        int verified = 0;
        int rejected = 0;
        int unavailable = 0;
        int eligibleIndex = 0;
        for (ReviewFindingResult finding : findings) {
            if (!LlmVerificationStatus.PENDING.name().equalsIgnoreCase(finding.verificationStatus())) {
                resolved.add(finding);
                continue;
            }
            eligibleIndex++;
            if (!properties.isEnabled()) {
                resolved.add(policyResolver.downgradeLlmCandidate(
                    finding,
                    LlmVerificationStatus.UNAVAILABLE,
                    "verification_disabled"
                ));
                unavailable++;
                continue;
            }
            if (eligibleIndex > properties.getMaxCandidates()) {
                resolved.add(policyResolver.downgradeLlmCandidate(
                    finding,
                    LlmVerificationStatus.LIMIT_EXCEEDED,
                    "max_candidates"
                ));
                unavailable++;
                continue;
            }
            if (budget.exhausted()) {
                resolved.add(policyResolver.downgradeLlmCandidate(
                    finding,
                    LlmVerificationStatus.UNAVAILABLE,
                    "pipeline_budget_exhausted"
                ));
                unavailable++;
                continue;
            }
            if (!context.llmReviewCaller().supportsHighRiskVerification()) {
                resolved.add(policyResolver.downgradeLlmCandidate(
                    finding,
                    LlmVerificationStatus.UNAVAILABLE,
                    "caller_does_not_support_verification"
                ));
                unavailable++;
                continue;
            }
            attempted++;
            try {
                LlmCallResult callResult = context.llmReviewCaller().verifyHighRisk(
                    context.settings(),
                    context.task(),
                    diff,
                    finding,
                    context.promptContext()
                );
                if (callResult == null) {
                    throw new IllegalStateException("High-risk verification returned no response");
                }
                usage = LlmCallResult.combine(usage, callResult);
                LlmHighRiskVerificationDecision decision = parser.apply(callResult.content());
                ReviewFindingResult verifiedFinding = policyResolver.resolveVerifiedLlmCandidate(
                    finding,
                    decision,
                    effectiveEnforcementMode(context.settings())
                );
                resolved.add(verifiedFinding);
                if (decision.verified()) {
                    verified++;
                } else {
                    rejected++;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn(
                    "LLM high-risk verification unavailable operation=llm_high_risk_verification "
                        + "result=degraded file={} line={} exceptionType={}",
                    finding.filePath(),
                    finding.lineNumber(),
                    ex.getClass().getName()
                );
                resolved.add(policyResolver.downgradeLlmCandidate(
                    finding,
                    LlmVerificationStatus.UNAVAILABLE,
                    ex.getClass().getSimpleName()
                ));
                unavailable++;
            }
        }
        List<ReviewFindingResult> immutable = List.copyOf(resolved);
        return new LlmHighRiskVerificationOutcome(
            ReviewResult.completed(riskAggregator.aggregate(immutable), immutable),
            usage,
            new LlmVerificationSummary(attempted, verified, rejected, unavailable)
        );
    }

    private EnforcementMode effectiveEnforcementMode(ReviewPolicySettings settings) {
        EnforcementMode configured = properties.enforcementMode();
        if (settings == null || settings.strategyRelease() == null) {
            return configured;
        }
        ReviewStrategyRelease release = settings.strategyRelease();
        if (!release.replayVerified() || !release.supportsRuntimeVersions()) {
            return EnforcementMode.OBSERVE;
        }
        return rank(release.enforcementMode()) <= rank(configured)
            ? release.enforcementMode()
            : configured;
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }
}
