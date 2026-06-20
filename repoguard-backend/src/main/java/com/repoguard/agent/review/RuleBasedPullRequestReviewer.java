package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleProvider;
import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RuleBasedPullRequestReviewer {

    private final ReviewRuleProvider reviewRuleProvider;

    public RuleBasedPullRequestReviewer(ReviewRuleProvider reviewRuleProvider) {
        this.reviewRuleProvider = reviewRuleProvider;
    }

    public ReviewResult review(GithubPullRequestDiff diff) {
        Map<String, ReviewRuleSettings> configuredRules = reviewRuleProvider.getRulesById();
        if (configuredRules == null) {
            configuredRules = Map.of();
        }
        List<ReviewFindingResult> findings = new ArrayList<>();
        for (GithubChangedFile file : diff.files()) {
            String patch = file.patch();
            if (patch == null || patch.isBlank()) {
                continue;
            }
            scanPatch(file.filename(), patch, configuredRules, findings);
        }
        return ReviewResult.completed(resolveRisk(findings), findings);
    }

    private void scanPatch(
        String filePath,
        String patch,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        String[] lines = patch.split("\\R");
        int currentLine = 0;
        for (String line : lines) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1);
                addFindingIfMatches(filePath, currentLine, added, configuredRules, findings);
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
        List<ReviewFindingResult> findings
    ) {
        String trimmed = line.trim();
        if (isApplicable("RG-JAVA-001", filePath, configuredRules)
            && (trimmed.contains("catch (Exception") || trimmed.contains("catch(Throwable") || trimmed.contains("catch (Throwable"))) {
            findings.add(finding("MEDIUM", "RG-JAVA-001", filePath, lineNumber, "新增代码捕获了过宽的异常类型", "请捕获更具体的异常类型，并保留必要的错误上下文。"));
        }
        if (isApplicable("RG-JAVA-002", filePath, configuredRules) && trimmed.contains("System.out.print")) {
            findings.add(finding("LOW", "RG-JAVA-002", filePath, lineNumber, "新增代码使用了标准输出日志", "请改用项目日志组件，避免生产日志不可控。"));
        }
        if (isApplicable("RG-JAVA-003", filePath, configuredRules) && trimmed.contains("Thread.sleep(")) {
            findings.add(finding("MEDIUM", "RG-JAVA-003", filePath, lineNumber, "新增代码包含固定休眠", "请使用可测试的等待条件、重试策略或调度机制。"));
        }
        if (isApplicable("RG-GEN-001", filePath, configuredRules) && (trimmed.contains("TODO") || trimmed.contains("FIXME"))) {
            findings.add(finding("LOW", "RG-GEN-001", filePath, lineNumber, "新增代码包含未收敛的 TODO/FIXME", "请在合并前补充实现或明确跟踪任务。"));
        }
    }

    private boolean isApplicable(String ruleId, String filePath, Map<String, ReviewRuleSettings> configuredRules) {
        ReviewRuleSettings rule = configuredRules.get(ruleId);
        if (rule == null) {
            return true;
        }
        if (rule.disabled()) {
            return false;
        }
        if (!rule.hasFilePatterns()) {
            return true;
        }
        return List.of(rule.filePatterns().split("[,\\n]")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(pattern -> matchesPattern(filePath, pattern));
    }

    private boolean matchesPattern(String filePath, String pattern) {
        String normalizedFilePath = normalizePath(filePath);
        String normalizedPattern = normalizePath(pattern);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        String regex = normalizedPattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return normalizedFilePath.matches(".*" + regex);
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private ReviewFindingResult finding(String severity, String ruleId, String filePath, Integer lineNumber, String message, String recommendation) {
        return new ReviewFindingResult(severity, "RULE", ruleId, filePath, lineNumber, message, recommendation);
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
