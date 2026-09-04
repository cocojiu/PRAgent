package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Merges repository policy with the server policy while preserving platform security floors. */
@Service
public class RepositoryPolicyEvaluationService {

    private static final int PLATFORM_MAX_TOKENS = 128_000;
    private static final BigDecimal PLATFORM_MAX_COST = new BigDecimal("1000.00");
    private static final Set<String> HIGH_IMPACT = Set.of("HIGH", "CRITICAL");

    private final ServerRiskAggregator riskAggregator;

    public RepositoryPolicyEvaluationService(ServerRiskAggregator riskAggregator) {
        this.riskAggregator = Objects.requireNonNull(riskAggregator, "riskAggregator");
    }

    public RepositoryPolicyEvaluation evaluate(
        ReviewPolicySettings adminSettings,
        Map<String, ReviewRuleSettings> adminRules,
        RepositoryPolicyDocument basePolicy,
        RepositoryPolicyDocument headPolicy,
        List<RepositoryPolicyDocument.SuppressionReference> storedSuppressions,
        List<String> sourceWarnings
    ) {
        ReviewPolicySettings serverSettings = adminSettings == null ? ReviewPolicySettings.empty() : adminSettings;
        RepositoryPolicyDocument base = basePolicy == null ? RepositoryPolicyDocument.empty() : basePolicy;
        Map<String, ReviewRuleSettings> rules = adminRules == null ? Map.of() : adminRules;
        List<String> warnings = new ArrayList<>();
        if (sourceWarnings != null) {
            sourceWarnings.stream().filter(StringUtils::hasText).map(String::trim).forEach(warnings::add);
        }
        Map<String, RuleDecision> decisions = ruleDecisions(rules, base, warnings);
        ReviewPolicySettings effectiveSettings = effectiveSettings(serverSettings, base, warnings);
        List<RepositoryPolicyDocument.SuppressionReference> suppressions = new ArrayList<>(base.suppressions());
        if (storedSuppressions != null) {
            storedSuppressions.stream().filter(Objects::nonNull).forEach(suppressions::add);
        }
        String commentMode = base.publication() == null || !StringUtils.hasText(base.publication().commentMode())
            ? "SUMMARY" : base.publication().commentMode();
        String checkMode = base.publication() == null || !StringUtils.hasText(base.publication().checkMode())
            ? "NEUTRAL" : base.publication().checkMode();
        BigDecimal costBudget = base.llm() == null ? null : base.llm().costBudget();
        return new RepositoryPolicyEvaluation(
            base,
            headPolicy,
            decisions,
            effectiveSettings,
            costBudget,
            commentMode,
            checkMode,
            suppressions,
            warnings
        );
    }

    public ReviewPolicySettings applyLlmSettings(
        ReviewPolicySettings serverSettings,
        RepositoryPolicyEvaluation evaluation
    ) {
        if (evaluation == null) {
            return serverSettings;
        }
        return evaluation.effectiveSettings();
    }

    public ReviewResult applyFindings(ReviewResult result, RepositoryPolicyEvaluation evaluation) {
        if (result == null || evaluation == null) {
            return result;
        }
        ReviewResult adjustedResult = result;
        if (result.findings() != null && !result.findings().isEmpty()) {
            List<ReviewFindingResult> adjusted = result.findings().stream()
                .filter(finding -> inRepositoryScope(finding, evaluation.basePolicy()))
                .filter(finding -> !disabledByPolicy(finding, evaluation))
                .filter(finding -> !suppressed(finding, evaluation.suppressions()))
                .map(finding -> adjustFinding(finding, evaluation))
                .toList();
            adjustedResult = adjusted.equals(result.findings()) ? result : new ReviewResult(
                riskAggregator.aggregate(adjusted),
                result.llmStatus(),
                result.statusDetail(),
                adjusted,
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
        if (evaluation.costBudget() != null
            && adjustedResult.llmEstimatedCost() != null
            && adjustedResult.llmEstimatedCost().compareTo(evaluation.costBudget()) > 0) {
            return adjustedResult.withIncompleteInput(
                "repository_policy_cost_budget_exceeded",
                "repositoryPolicyCostBudgetExceeded=true"
            );
        }
        return adjustedResult;
    }

    private boolean inRepositoryScope(
        ReviewFindingResult finding,
        RepositoryPolicyDocument policy
    ) {
        if (policy == null) {
            return true;
        }
        String path = finding.filePath();
        if (policy.excludePatterns().stream()
            .anyMatch(pattern -> ReviewRuleApplicability.matchesPathPattern(path, pattern))) {
            return false;
        }
        return policy.includePatterns().isEmpty()
            || policy.includePatterns().stream()
                .anyMatch(pattern -> ReviewRuleApplicability.matchesPathPattern(path, pattern));
    }

    private Map<String, RuleDecision> ruleDecisions(
        Map<String, ReviewRuleSettings> adminRules,
        RepositoryPolicyDocument base,
        List<String> warnings
    ) {
        Map<String, RuleDecision> decisions = new LinkedHashMap<>();
        for (Map.Entry<String, ReviewRuleSettings> entry : adminRules.entrySet()) {
            ReviewRuleSettings admin = entry.getValue();
            String id = entry.getKey().toUpperCase(Locale.ROOT);
            RepositoryPolicyDocument.RuleOverride override = base.rules().get(id);
            Boolean baseEnabled = override == null ? null : override.enabled();
            String baseSeverity = override == null ? null : normalize(override.severity());
            EnforcementMode baseEnforcement = override == null ? null : override.enforcementMode();
            boolean effectiveEnabled = baseEnabled == null ? !admin.disabled() : baseEnabled;
            String effectiveSeverity = baseSeverity == null ? admin.severity() : baseSeverity;
            EnforcementMode effectiveEnforcement = baseEnforcement == null
                ? admin.enforcementMode() : baseEnforcement;
            String conflict = null;
            if (baseEnabled != null && !baseEnabled && !admin.disabled()
                && HIGH_IMPACT.contains(admin.severity()) && admin.enforcementMode() == EnforcementMode.BLOCK) {
                effectiveEnabled = true;
                conflict = "platform_floor_keeps_blocking_rule_enabled";
            }
            if (severityRank(admin.severity()) >= severityRank("HIGH")
                && severityRank(effectiveSeverity) < severityRank(admin.severity())) {
                effectiveSeverity = admin.severity();
                conflict = "platform_floor_prevents_severity_downgrade";
            }
            if (admin.enforcementMode() == EnforcementMode.BLOCK
                && effectiveEnforcement != EnforcementMode.BLOCK) {
                effectiveEnforcement = EnforcementMode.BLOCK;
                conflict = "platform_floor_prevents_enforcement_downgrade";
            }
            if (conflict != null) {
                warnings.add(id + ":" + conflict);
            }
            decisions.put(id, new RuleDecision(
                id,
                !admin.disabled(),
                baseEnabled,
                effectiveEnabled,
                admin.severity(),
                baseSeverity,
                effectiveSeverity,
                admin.enforcementMode(),
                baseEnforcement,
                effectiveEnforcement,
                conflict
            ));
        }
        base.rules().keySet().stream()
            .filter(id -> !decisions.containsKey(id))
            .forEach(id -> warnings.add(id + ":unknown_rule_ignored"));
        return Map.copyOf(decisions);
    }

    private ReviewPolicySettings effectiveSettings(
        ReviewPolicySettings server,
        RepositoryPolicyDocument base,
        List<String> warnings
    ) {
        RepositoryPolicyDocument.LlmOverride override = base.llm();
        if (override == null) {
            return server;
        }
        boolean enabled = server.enabled();
        if (override.enabled() != null) {
            if (override.enabled() && !server.enabled()) {
                warnings.add("llm_enabled_by_repository_ignored_until_server_policy_is_enabled");
            } else {
                enabled = override.enabled();
            }
        }
        Integer maxTokens = server.maxTokens();
        if (override.tokenBudget() != null) {
            maxTokens = Math.min(override.tokenBudget(), PLATFORM_MAX_TOKENS);
        }
        if (maxTokens != null && maxTokens > PLATFORM_MAX_TOKENS) {
            maxTokens = PLATFORM_MAX_TOKENS;
            warnings.add("llm_token_budget_capped_by_platform");
        }
        return new ReviewPolicySettings(
            server.exists(),
            enabled,
            server.llmProvider(),
            server.modelName(),
            server.baseUrl(),
            server.apiKey(),
            server.timeoutSeconds(),
            server.temperature(),
            maxTokens,
            server.fallbackToRules(),
            server.workerConcurrency(),
            server.chunkFileThreshold(),
            server.chunkLineThreshold(),
            server.chunkMaxFiles(),
            server.chunkMaxLines(),
            server.inputTokenPricePerMillion(),
            server.outputTokenPricePerMillion(),
            server.strategyRelease()
        );
    }

    private boolean disabledByPolicy(ReviewFindingResult finding, RepositoryPolicyEvaluation evaluation) {
        if (!StringUtils.hasText(finding.ruleId())) {
            return false;
        }
        RuleDecision decision = evaluation.rules().get(finding.ruleId().trim().toUpperCase(Locale.ROOT));
        return decision != null && !decision.effectiveEnabled();
    }

    private boolean suppressed(
        ReviewFindingResult finding,
        List<RepositoryPolicyDocument.SuppressionReference> suppressions
    ) {
        if (suppressions == null || suppressions.isEmpty()) {
            return false;
        }
        OffsetDateTimeHolder now = OffsetDateTimeHolder.now();
        for (RepositoryPolicyDocument.SuppressionReference suppression : suppressions) {
            if (!suppression.ruleId().equalsIgnoreCase(value(finding.ruleId()))
                || suppression.expiresAt().isBefore(now.value())) {
                continue;
            }
            boolean fileMatches = !StringUtils.hasText(suppression.fileGlob())
                || ReviewRuleApplicability.matchesPathPattern(finding.filePath(), suppression.fileGlob());
            boolean symbolMatches = !StringUtils.hasText(suppression.symbol())
                || contains(finding.message(), suppression.symbol())
                || contains(finding.recommendation(), suppression.symbol());
            if (fileMatches && symbolMatches) {
                return true;
            }
        }
        return false;
    }

    private ReviewFindingResult adjustFinding(ReviewFindingResult finding, RepositoryPolicyEvaluation evaluation) {
        if (!StringUtils.hasText(finding.ruleId())) {
            return finding;
        }
        RuleDecision decision = evaluation.rules().get(finding.ruleId().trim().toUpperCase(Locale.ROOT));
        if (decision == null) {
            return finding;
        }
        String severity = decision.effectiveSeverity();
        EnforcementMode configuredMode = decision.effectiveEnforcement();
        EnforcementMode mode = capByStrategy(configuredMode, evaluation.effectiveSettings());
        boolean blocking = mode == EnforcementMode.BLOCK
            && (finding.isBlocking() || finding.blockingCandidate())
            && HIGH_IMPACT.contains(severity)
            && "HIGH".equalsIgnoreCase(finding.confidence());
        String policyReason = decision.conflict() == null ? finding.policyReason() : decision.conflict();
        if (mode != configuredMode) {
            policyReason = appendStrategyCapReason(policyReason, mode);
        }
        if (severity.equalsIgnoreCase(finding.severity())
            && mode.name().equalsIgnoreCase(finding.enforcementMode())
            && blocking == finding.isBlocking()) {
            return finding;
        }
        return new ReviewFindingResult(
            severity,
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
            mode.name(),
            policyReason,
            finding.issueType(),
            finding.preconditions(),
            finding.relatedFiles(),
            finding.blockingCandidate(),
            finding.verificationStatus(),
            finding.provenance()
        );
    }

    /**
     * A repository rule may retain its platform floor in the policy decision, but it must not
     * bypass the active server strategy when a finding is materialized.  In particular, the
     * OBSERVE baseline is required to produce evidence without comments or blocking decisions.
     */
    private EnforcementMode capByStrategy(EnforcementMode configuredMode, ReviewPolicySettings settings) {
        EnforcementMode strategyMode = effectiveStrategyMode(settings == null ? null : settings.strategyRelease());
        return rank(configuredMode) <= rank(strategyMode) ? configuredMode : strategyMode;
    }

    private EnforcementMode effectiveStrategyMode(ReviewStrategyRelease release) {
        if (release == null || !release.replayVerified() || !release.supportsRuntimeVersions()) {
            return EnforcementMode.OBSERVE;
        }
        return release.enforcementMode();
    }

    private String appendStrategyCapReason(String current, EnforcementMode effectiveMode) {
        String marker = "strategy_enforcement_cap_" + effectiveMode.name().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(current)) {
            return marker;
        }
        String separator = "; ";
        int availableCurrentLength = 255 - separator.length() - marker.length();
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

    private int severityRank(String value) {
        return switch (normalize(value)) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean contains(String value, String token) {
        return StringUtils.hasText(value) && StringUtils.hasText(token)
            && value.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    public record RuleDecision(
        String ruleId,
        boolean adminEnabled,
        Boolean baseEnabled,
        boolean effectiveEnabled,
        String adminSeverity,
        String baseSeverity,
        String effectiveSeverity,
        EnforcementMode adminEnforcement,
        EnforcementMode baseEnforcement,
        EnforcementMode effectiveEnforcement,
        String conflict
    ) {
    }

    public record RepositoryPolicyEvaluation(
        RepositoryPolicyDocument basePolicy,
        RepositoryPolicyDocument headPolicy,
        Map<String, RuleDecision> rules,
        ReviewPolicySettings effectiveSettings,
        BigDecimal costBudget,
        String commentMode,
        String checkMode,
        List<RepositoryPolicyDocument.SuppressionReference> suppressions,
        List<String> warnings
    ) {

        public RepositoryPolicyEvaluation {
            rules = rules == null ? Map.of() : Map.copyOf(rules);
            suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private record OffsetDateTimeHolder(java.time.OffsetDateTime value) {
        static OffsetDateTimeHolder now() {
            return new OffsetDateTimeHolder(java.time.OffsetDateTime.now());
        }
    }
}
