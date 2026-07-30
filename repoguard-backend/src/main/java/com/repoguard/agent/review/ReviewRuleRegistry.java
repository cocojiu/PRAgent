package com.repoguard.agent.review;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class ReviewRuleRegistry {

    private final List<ReviewRule> lineRules;
    private final List<PullRequestReviewRule> pullRequestRules;
    private final Map<String, String> detectorVersions;
    private final Set<String> ruleIds;

    public ReviewRuleRegistry(List<ReviewRule> lineRules, List<PullRequestReviewRule> pullRequestRules) {
        this.lineRules = sortedLineRules(lineRules);
        this.pullRequestRules = sortedPullRequestRules(pullRequestRules);
        this.detectorVersions = collectUniqueDetectors(this.lineRules, this.pullRequestRules);
        this.ruleIds = Set.copyOf(detectorVersions.keySet());
        if (ruleIds.isEmpty()) {
            throw new IllegalArgumentException("At least one review rule detector must be registered");
        }
    }

    public boolean contains(String ruleId) {
        return ruleIds.contains(normalizeId(ruleId));
    }

    public Set<String> ruleIds() {
        return ruleIds;
    }

    public String detectorVersion(String ruleId) {
        String normalized = normalizeId(ruleId);
        String version = detectorVersions.get(normalized);
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("Review rule detector is not registered: " + normalized);
        }
        return version;
    }

    List<ReviewRule> lineRules() {
        return lineRules;
    }

    List<PullRequestReviewRule> pullRequestRules() {
        return pullRequestRules;
    }

    private static List<ReviewRule> sortedLineRules(List<ReviewRule> rules) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
            .sorted(Comparator.comparingInt(ReviewRule::order).thenComparing(ReviewRule::id))
            .toList();
    }

    private static List<PullRequestReviewRule> sortedPullRequestRules(List<PullRequestReviewRule> rules) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
            .sorted(Comparator.comparingInt(PullRequestReviewRule::order).thenComparing(PullRequestReviewRule::id))
            .toList();
    }

    private static Map<String, String> collectUniqueDetectors(
        List<ReviewRule> lineRules,
        List<PullRequestReviewRule> pullRequestRules
    ) {
        Map<String, String> detectorTypes = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        lineRules.forEach(rule -> register(detectorTypes, versions, rule.id(), rule.version(), "line"));
        pullRequestRules.forEach(rule -> register(
            detectorTypes,
            versions,
            rule.id(),
            rule.version(),
            "pull-request"
        ));
        return Map.copyOf(versions);
    }

    private static void register(
        Map<String, String> detectorTypes,
        Map<String, String> versions,
        String rawId,
        String rawVersion,
        String detectorType
    ) {
        String id = normalizeId(rawId);
        if (!StringUtils.hasText(id)) {
            throw new IllegalStateException("Review rule detector id must not be blank");
        }
        String existing = detectorTypes.putIfAbsent(id, detectorType);
        if (existing != null) {
            throw new IllegalStateException(
                "Duplicate review rule detector id " + id + " (" + existing + ", " + detectorType + ")"
            );
        }
        if (!StringUtils.hasText(rawVersion)) {
            throw new IllegalStateException("Review rule detector version must not be blank: " + id);
        }
        versions.put(id, rawVersion.trim());
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
