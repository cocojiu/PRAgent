package com.repoguard.agent.review;

import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

final class ReviewStrategyEnforcementGate {

    private static final int MAX_POLICY_REASON_LENGTH = 255;

    private final ServerRiskAggregator riskAggregator = new ServerRiskAggregator();

    ReviewResult apply(ReviewResult result, ReviewStrategyRelease release) {
        if (result == null) {
            return null;
        }
        EnforcementMode strategyMode = effectiveStrategyMode(release);
        List<ReviewFindingResult> findings = result.findings() == null
            ? List.of()
            : result.findings().stream()
                .filter(Objects::nonNull)
                .map(finding -> cap(finding, strategyMode))
                .toList();
        return new ReviewResult(
            riskAggregator.aggregate(findings),
            result.llmStatus(),
            result.statusDetail(),
            findings,
            result.llmProvider(),
            result.llmModel(),
            result.llmDurationMs(),
            result.llmParseStatus(),
            result.llmPromptSummary(),
            result.llmPromptTokens(),
            result.llmCompletionTokens(),
            result.llmTotalTokens(),
            result.llmEstimatedCost(),
            result.executionProvenance()
        );
    }

    private ReviewFindingResult cap(ReviewFindingResult finding, EnforcementMode strategyMode) {
        EnforcementMode findingMode = parseMode(finding.enforcementMode());
        EnforcementMode effectiveMode = rank(findingMode) <= rank(strategyMode) ? findingMode : strategyMode;
        boolean blocking = finding.isBlocking() && effectiveMode == EnforcementMode.BLOCK;
        boolean canonicalMode = effectiveMode.name().equals(finding.enforcementMode());
        if (canonicalMode && blocking == finding.isBlocking()) {
            return finding;
        }
        return new ReviewFindingResult(
            finding.severity(),
            finding.source(),
            finding.ruleId(),
            finding.filePath(),
            finding.lineNumber(),
            finding.message(),
            finding.recommendation(),
            finding.confidence(),
            finding.evidence(),
            finding.impact(),
            finding.fixExample(),
            blocking,
            finding.reviewDimension(),
            effectiveMode.name(),
            cappedReason(finding.policyReason(), effectiveMode),
            finding.issueType(),
            finding.preconditions(),
            finding.relatedFiles(),
            finding.blockingCandidate() || finding.isBlocking(),
            finding.verificationStatus(),
            finding.provenance()
        );
    }

    private EnforcementMode effectiveStrategyMode(ReviewStrategyRelease release) {
        if (release == null || !release.replayVerified() || !release.supportsRuntimeVersions()) {
            return EnforcementMode.OBSERVE;
        }
        return release.enforcementMode();
    }

    private EnforcementMode parseMode(String value) {
        try {
            return EnforcementMode.from(value);
        } catch (IllegalArgumentException ignored) {
            return EnforcementMode.OBSERVE;
        }
    }

    private String cappedReason(String current, EnforcementMode effectiveMode) {
        String marker = "strategy_enforcement_cap_" + effectiveMode.name().toLowerCase();
        if (!StringUtils.hasText(current)) {
            return marker;
        }
        String separator = "; ";
        int availableCurrentLength = MAX_POLICY_REASON_LENGTH - separator.length() - marker.length();
        String normalizedCurrent = current.trim();
        String retainedCurrent = normalizedCurrent.length() <= availableCurrentLength
            ? normalizedCurrent
            : normalizedCurrent.substring(0, availableCurrentLength);
        return retainedCurrent + separator + marker;
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }
}
