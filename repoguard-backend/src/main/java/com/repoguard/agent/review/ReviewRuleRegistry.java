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
    private final Set<String> ruleIds;

    public ReviewRuleRegistry(List<ReviewRule> lineRules, List<PullRequestReviewRule> pullRequestRules) {
        this.lineRules = sortedLineRules(lineRules);
        this.pullRequestRules = sortedPullRequestRules(pullRequestRules);
        this.ruleIds = collectUniqueIds(this.lineRules, this.pullRequestRules);
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

    private static Set<String> collectUniqueIds(
        List<ReviewRule> lineRules,
        List<PullRequestReviewRule> pullRequestRules
    ) {
        Map<String, String> detectorTypes = new LinkedHashMap<>();
        lineRules.forEach(rule -> register(detectorTypes, rule.id(), "line"));
        pullRequestRules.forEach(rule -> register(detectorTypes, rule.id(), "pull-request"));
        return Set.copyOf(detectorTypes.keySet());
    }

    private static void register(Map<String, String> detectorTypes, String rawId, String detectorType) {
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
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
