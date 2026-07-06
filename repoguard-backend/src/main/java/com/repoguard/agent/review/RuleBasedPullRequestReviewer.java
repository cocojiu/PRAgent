package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleProvider;
import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedPullRequestReviewer {

    private final ReviewRuleProvider reviewRuleProvider;
    private final ReviewFindingFactory findingFactory;
    private final List<ReviewRule> lineRules;
    private final ControllerApiTestCoverageRule apiTestCoverageRule;

    @Autowired
    public RuleBasedPullRequestReviewer(
        ReviewRuleProvider reviewRuleProvider,
        ReviewFindingFactory findingFactory,
        List<ReviewRule> lineRules,
        ControllerApiTestCoverageRule apiTestCoverageRule
    ) {
        this.reviewRuleProvider = reviewRuleProvider;
        this.findingFactory = findingFactory;
        this.lineRules = sortedRules(lineRules);
        this.apiTestCoverageRule = apiTestCoverageRule;
    }

    RuleBasedPullRequestReviewer(
        ReviewRuleProvider reviewRuleProvider,
        ReviewFindingFactory findingFactory,
        List<ReviewRule> lineRules
    ) {
        this(reviewRuleProvider, findingFactory, lineRules, new ControllerApiTestCoverageRule(findingFactory));
    }

    private static List<ReviewRule> sortedRules(List<ReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("At least one ReviewRule plugin must be registered");
        }
        return rules.stream()
            .sorted(Comparator.comparingInt(ReviewRule::order).thenComparing(ReviewRule::id))
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
        apiTestCoverageRule.evaluate(diff, configuredRules).ifPresent(findings::add);
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
                addFindingIfMatches(filePath, currentLine, added, configuredRules, findings, hasAuthorizationGuard);
                currentLine++;
            } else if (!line.startsWith("-")) {
                currentLine++;
            }
        }
    }

    private void addFindingIfMatches(
        String filePath,
        int lineNumber,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings,
        boolean hasAuthorizationGuard
    ) {
        String trimmed = line.trim();
        evaluateLineRules(filePath, lineNumber, line, trimmed, configuredRules, findings);
        if (ReviewRuleApplicability.isApplicable("RG-AUTH-001", filePath, configuredRules)
            && isMutatingControllerMapping(filePath, trimmed)
            && !hasAuthorizationGuard) {
            findings.add(finding("HIGH", "RG-AUTH-001", filePath, lineNumber, "新增高危 Controller 写接口缺少显式权限门禁", "请为配置写入、评论回写、用户管理等写接口补充 @RequireRole 或等效网关权限控制。"));
        }
    }

    private void evaluateLineRules(
        String filePath,
        int lineNumber,
        String line,
        String trimmed,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        ReviewRuleLineContext context = new ReviewRuleLineContext(filePath, lineNumber, line, trimmed, configuredRules);
        for (ReviewRule rule : lineRules) {
            rule.evaluate(context).ifPresent(findings::add);
        }
    }

    private boolean isMutatingControllerMapping(String filePath, String trimmed) {
        String normalizedPath = ReviewRuleApplicability.normalizePath(filePath);
        if (!normalizedPath.endsWith("controller.java")) {
            return false;
        }
        return trimmed.contains("@PostMapping") || trimmed.contains("@PutMapping") || trimmed.contains("@PatchMapping")
            || trimmed.contains("@DeleteMapping");
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

    private ReviewFindingResult finding(String severity, String ruleId, String filePath, Integer lineNumber, String message, String recommendation) {
        return findingFactory.finding(severity, ruleId, filePath, lineNumber, message, recommendation);
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
