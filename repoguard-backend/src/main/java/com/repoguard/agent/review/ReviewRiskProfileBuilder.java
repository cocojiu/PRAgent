package com.repoguard.agent.review;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewRiskProfileBuilder {

    public PrRiskProfileDto build(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<ChangedFileDto> changedFiles
    ) {
        Map<String, Long> findingCountByFile = findings.stream()
            .filter(finding -> StringUtils.hasText(finding.file()))
            .collect(Collectors.groupingBy(ReviewFindingDto::file, Collectors.counting()));
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        int lowCount = countSeverity(findings, "low");
        int totalChurn = changedFiles.stream()
            .mapToInt(file -> safeInt(file.additions()) + safeInt(file.deletions()))
            .sum();
        int sensitiveFileCount = (int) changedFiles.stream().filter(file -> !riskReasons(file).isEmpty()).count();

        int score = criticalCount * 35
            + highCount * 25
            + mediumCount * 12
            + lowCount * 4
            + Math.min(changedFiles.size() * 2, 20)
            + Math.min(totalChurn / 50, 20)
            + Math.min(sensitiveFileCount * 8, 24);
        score = Math.min(score, 100);
        String level = scoreToRiskLevel(score, task.riskLevel());

        List<String> signals = new java.util.ArrayList<>();
        if (criticalCount + highCount > 0) {
            signals.add("包含 " + (criticalCount + highCount) + " 条高危以上发现");
        }
        if (mediumCount > 0) {
            signals.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (changedFiles.size() >= 8) {
            signals.add("变更文件较多：" + changedFiles.size() + " 个文件");
        }
        if (totalChurn >= 300) {
            signals.add("变更规模较大：" + totalChurn + " 行增删");
        }
        if (sensitiveFileCount > 0) {
            signals.add("触及 " + sensitiveFileCount + " 个敏感文件");
        }
        if (signals.isEmpty()) {
            signals.add("未发现明显放大风险的变更信号");
        }

        boolean recommendHumanReview = score >= 55 || Boolean.TRUE.equals(task.humanReviewRequired());
        String humanReviewReason = recommendHumanReview
            ? "风险分达到 " + score + "，建议人工复核后再回写或合并。"
            : "风险分较低，可按常规自动审查流程推进。";
        List<PrRiskFileDto> highRiskFiles = changedFiles.stream()
            .map(file -> toRiskFile(file, findingCountByFile.getOrDefault(file.path(), 0L).intValue()))
            .filter(file -> file.score() > 0)
            .sorted(Comparator.comparing(PrRiskFileDto::score).reversed())
            .limit(5)
            .toList();

        return new PrRiskProfileDto(
            score,
            level,
            buildRiskSummary(level, score, findings.size(), changedFiles.size(), totalChurn),
            recommendHumanReview,
            humanReviewReason,
            signals,
            highRiskFiles
        );
    }

    private PrRiskFileDto toRiskFile(ChangedFileDto file, int findingCount) {
        List<String> reasons = riskReasons(file);
        int churn = safeInt(file.additions()) + safeInt(file.deletions());
        int score = findingCount * 18 + Math.min(churn / 25, 20) + reasons.size() * 12;
        return new PrRiskFileDto(
            file.path(),
            file.changeType(),
            file.additions(),
            file.deletions(),
            findingCount,
            Math.min(score, 100),
            reasons
        );
    }

    private List<String> riskReasons(ChangedFileDto file) {
        String path = file.path() == null ? "" : file.path().toLowerCase(Locale.ROOT);
        List<String> reasons = new java.util.ArrayList<>();
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            reasons.add("数据库迁移");
        }
        if (path.contains("security") || path.contains("auth") || path.contains("token") || path.contains("permission")) {
            reasons.add("认证或权限");
        }
        if (path.endsWith("application.yml") || path.endsWith("application-prod.yml") || path.contains("config")) {
            reasons.add("运行配置");
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            reasons.add("构建或发布链路");
        }
        return reasons;
    }

    private int countSeverity(List<ReviewFindingDto> findings, String severity) {
        return (int) findings.stream().filter(finding -> severity.equalsIgnoreCase(finding.severity())).count();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String scoreToRiskLevel(int score, String fallbackRiskLevel) {
        if (score >= 80 || "critical".equalsIgnoreCase(fallbackRiskLevel)) {
            return "critical";
        }
        if (score >= 55 || "high".equalsIgnoreCase(fallbackRiskLevel)) {
            return "high";
        }
        if (score >= 30 || "medium".equalsIgnoreCase(fallbackRiskLevel)) {
            return "medium";
        }
        if (score >= 10 || "low".equalsIgnoreCase(fallbackRiskLevel)) {
            return "low";
        }
        return "info";
    }

    private String buildRiskSummary(String level, int score, int findingCount, int fileCount, int totalChurn) {
        return "本次 PR 综合风险为 " + lower(level)
            + "（" + score + "/100），覆盖 "
            + fileCount + " 个变更文件、" + totalChurn + " 行增删，审查发现 "
            + findingCount + " 条。";
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
