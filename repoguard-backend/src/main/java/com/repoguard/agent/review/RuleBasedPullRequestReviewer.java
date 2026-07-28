package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedPullRequestReviewer {

    private final ReviewRuleProvider reviewRuleProvider;
    private final List<ReviewRule> lineRules;
    private final List<PullRequestReviewRule> pullRequestRules;

    @Autowired
    public RuleBasedPullRequestReviewer(
        ReviewRuleProvider reviewRuleProvider,
        List<ReviewRule> lineRules,
        List<PullRequestReviewRule> pullRequestRules
    ) {
        this.reviewRuleProvider = Objects.requireNonNull(reviewRuleProvider, "reviewRuleProvider");
        this.lineRules = sortedRules(lineRules);
        this.pullRequestRules = sortedPullRequestRules(pullRequestRules);
    }

    private static List<ReviewRule> sortedRules(List<ReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("At least one ReviewRule plugin must be registered");
        }
        return rules.stream()
            .sorted(Comparator.comparingInt(ReviewRule::order).thenComparing(ReviewRule::id))
            .toList();
    }

    private static List<PullRequestReviewRule> sortedPullRequestRules(List<PullRequestReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
            .sorted(Comparator.comparingInt(PullRequestReviewRule::order).thenComparing(PullRequestReviewRule::id))
            .toList();
    }

    public ReviewResult review(GithubPullRequestDiff diff) {
        Map<String, ReviewRuleSettings> configuredRules = reviewRuleProvider.getRulesById();
        if (configuredRules == null) {
            configuredRules = Map.of();
        }
        List<ReviewFindingResult> findings = new ArrayList<>();
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        for (GithubChangedFile file : files) {
            String patch = file.patch();
            if (patch == null || patch.isBlank()) {
                continue;
            }
            scanPatch(file.filename(), patch, configuredRules, findings);
        }
        scanPullRequestLevelRules(diff, configuredRules, findings);
        return ReviewResult.completed(resolveRisk(findings), findings);
    }

    private void scanPullRequestLevelRules(
        GithubPullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        for (PullRequestReviewRule rule : pullRequestRules) {
            findings.addAll(rule.evaluate(diff, configuredRules));
        }
    }

    private void scanPatch(
        String filePath,
        String patch,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        String[] lines = patch.split("\\R");
        int currentLine = 0;
        boolean hasAuthorizationGuard = patchHasAuthorizationGuard(lines);
        for (String line : lines) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1);
                evaluateLineRules(filePath, currentLine, added, configuredRules, findings, hasAuthorizationGuard);
                currentLine++;
            } else if (!line.startsWith("-")) {
                currentLine++;
            }
        }
    }

    private void evaluateLineRules(
        String filePath,
        int lineNumber,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings,
        boolean hasAuthorizationGuard
    ) {
        ReviewRuleLineContext context = new ReviewRuleLineContext(
            filePath,
            lineNumber,
            line,
            line.trim(),
            configuredRules,
            hasAuthorizationGuard
        );
        for (ReviewRule rule : lineRules) {
            rule.evaluate(context).ifPresent(findings::add);
        }
    }

    private boolean patchHasAuthorizationGuard(String[] lines) {
        for (String line : lines) {
            if (!line.startsWith("-") && hasAuthorizationGuard(line.replaceFirst("^[+ ]", "").trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAuthorizationGuard(String trimmed) {
        return trimmed.contains("@RequireRole")
            || trimmed.contains("@RequirePermission")
            || trimmed.contains("@PreAuthorize")
            || trimmed.contains("@Secured")
            || trimmed.contains("@RolesAllowed");
    }

    private int parseNewFileStart(String hunkHeader) {
        int marker = hunkHeader.indexOf('+');
        if (marker < 0) {
            return 0;
        }
        int end = hunkHeader.indexOf(' ', marker);
        String range = (end < 0 ? hunkHeader.substring(marker + 1) : hunkHeader.substring(marker + 1, end)).trim();
        int comma = range.indexOf(',');
        String start = comma < 0 ? range : range.substring(0, comma);
        try {
            return Integer.parseInt(start);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String resolveRisk(List<ReviewFindingResult> findings) {
        if (findings.stream().anyMatch(finding -> "HIGH".equals(finding.severity()))) {
            return "HIGH";
        }
        if (findings.stream().anyMatch(finding -> "MEDIUM".equals(finding.severity()))) {
            return "MEDIUM";
        }
        if (findings.stream().anyMatch(finding -> "LOW".equals(finding.severity()))) {
            return "LOW";
        }
        return "INFO";
    }
}
