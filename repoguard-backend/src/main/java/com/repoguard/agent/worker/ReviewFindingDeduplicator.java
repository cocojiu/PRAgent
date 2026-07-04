package com.repoguard.agent.worker;

import com.repoguard.agent.review.ReviewFindingResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingDeduplicator {

    private final ReviewFindingDeduplicationKeyResolver keyResolver;

    ReviewFindingDeduplicator(ReviewFindingDeduplicationKeyResolver keyResolver) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
    }

    List<ReviewFindingResult> deduplicate(List<ReviewFindingResult> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ReviewFindingResult> byKey = new LinkedHashMap<>();
        for (ReviewFindingResult finding : findings) {
            String key = keyResolver.key(finding);
            ReviewFindingResult existing = byKey.get(key);
            byKey.put(key, existing == null ? finding : mergeFinding(existing, finding));
        }
        return new ArrayList<>(byKey.values());
    }

    private ReviewFindingResult mergeFinding(ReviewFindingResult first, ReviewFindingResult second) {
        ReviewFindingResult stronger = riskRank(second.severity()) > riskRank(first.severity()) ? second : first;
        return new ReviewFindingResult(
            stronger.severity(),
            mergeSource(first.source(), second.source()),
            mergeText(first.ruleId(), second.ruleId()),
            stronger.filePath(),
            stronger.lineNumber(),
            stronger.message(),
            mergeText(first.recommendation(), second.recommendation()),
            stronger.confidence(),
            mergeText(first.evidence(), second.evidence()),
            mergeText(first.impact(), second.impact()),
            mergeText(first.fixExample(), second.fixExample()),
            first.isBlocking() || second.isBlocking(),
            mergeText(first.reviewDimension(), second.reviewDimension())
        );
    }

    private String mergeSource(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        if (containsSource(left, "LLM") && containsSource(right, "RULE")
            || containsSource(left, "RULE") && containsSource(right, "LLM")) {
            return "LLM+RULE";
        }
        return left + " / " + right;
    }

    private boolean containsSource(String value, String source) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(source);
    }

    private String mergeText(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        return left + " / " + right;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private int riskRank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }
}
