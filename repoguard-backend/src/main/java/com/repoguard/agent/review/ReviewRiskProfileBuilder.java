package com.repoguard.agent.review;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewRiskProfileBuilder {

    public PrRiskProfileDto build(
        ReviewTaskListItem task, List<ReviewFindingDto> findings, List<ChangedFileDto> changedFiles
    ) {
        List<ReviewFindingDto> effectiveFindings = findings.stream()
            .filter(finding -> !"false_positive".equalsIgnoreCase(finding.feedbackStatus()))
            .filter(finding -> !"observe".equalsIgnoreCase(finding.enforcementMode()))
            .toList();
        Map<String, Long> findingCountByFile = effectiveFindings.stream()
            .filter(finding -> StringUtils.hasText(finding.file()))
            .collect(Collectors.groupingBy(ReviewFindingDto::file, Collectors.counting()));
        FindingSeverityCountsDto counts = new FindingSeverityCountsDto(
            (long) countSeverity(effectiveFindings, "critical"), (long) countSeverity(effectiveFindings, "high"),
            (long) countSeverity(effectiveFindings, "medium"), (long) countSeverity(effectiveFindings, "low"), 0L
        );
        ChangeRisk change = new ChangeRisk(changedFiles.size(), changedFiles.stream()
            .mapToInt(file -> safeInt(file.additions()) + safeInt(file.deletions())).sum(),
            (int) changedFiles.stream().filter(file -> !riskReasons(file).isEmpty()).count(), false);
        List<PrRiskFileDto> highRiskFiles = changedFiles.stream()
            .map(file -> toRiskFile(file, findingCountByFile.getOrDefault(file.path(), 0L).intValue()))
            .filter(file -> file.score() > 0)
            .sorted(Comparator.comparing(PrRiskFileDto::score).reversed())
            .limit(5)
            .toList();
        return buildProfile(task, counts, effectiveFindings.size(), change, highRiskFiles);
    }

    public PrRiskProfileDto buildSummary(
        ReviewTaskListItem task, FindingSeverityCountsDto severityCounts, long findingTotal, long changedFileTotal
    ) {
        return buildProfile(task, severityCounts == null ? FindingSeverityCountsDto.empty() : severityCounts,
            findingTotal, new ChangeRisk(changedFileTotal, 0, 0, true), List.of());
    }

    private PrRiskProfileDto buildProfile(
        ReviewTaskListItem task, FindingSeverityCountsDto counts, long findingTotal,
        ChangeRisk change, List<PrRiskFileDto> highRiskFiles
    ) {
        Optional<String> conclusionBlock = AssessmentStatus.conclusionBlockReason(task);
        long blockingCount = counts.criticalOrZero() + counts.highOrZero();
        int score = Math.toIntExact(Math.min(counts.criticalOrZero() * 35 + counts.highOrZero() * 25
            + counts.mediumOrZero() * 12 + counts.lowOrZero() * 4 + Math.min(change.files() * 2, 20)
            + Math.min(change.churn() / 50, 20) + Math.min(change.sensitiveFiles() * 8, 24), 100));
        String level = scoreToRiskLevel(score, task.riskLevel());
        List<String> signals = new java.util.ArrayList<>();
        if (!conclusionBlock.isEmpty()) {
            signals.add(conclusionBlock.orElseThrow());
        }
        if (blockingCount > 0) {
            signals.add("包含 " + blockingCount + " 条高危以上发现");
        }
        if (counts.mediumOrZero() > 0) {
            signals.add("包含 " + counts.mediumOrZero() + " 条中风险发现");
        }
        if (change.files() >= 8) {
            signals.add("变更文件较多：" + change.files() + " 个文件");
        }
        if (change.churn() >= 300) {
            signals.add("变更规模较大：" + change.churn() + " 行增删");
        }
        if (change.sensitiveFiles() > 0) {
            signals.add("触及 " + change.sensitiveFiles() + " 个敏感文件");
        }
        if (signals.isEmpty()) {
            signals.add("未发现明显放大风险的变更信号");
        }
        boolean recommendHumanReview = (change.summaryOnly() && blockingCount > 0)
            || score >= 55 || Boolean.TRUE.equals(task.humanReviewRequired());
        String reason = !conclusionBlock.isEmpty() ? conclusionBlock.orElseThrow() : recommendHumanReview
            ? "风险分达到 " + score + "，建议人工复核后再回写或合并。" : "风险分较低，可按常规自动审查流程推进。";
        String summary = conclusionBlock.isEmpty()
            ? "本次 PR 综合风险为 " + level + "（" + score + "/100），覆盖 " + change.files() + " 个变更文件"
                + (change.summaryOnly() ? "" : "、" + change.churn() + " 行增删") + "，审查发现 " + findingTotal + " 条。"
            : conclusionBlock.orElseThrow();
        return new PrRiskProfileDto(score, level, summary, recommendHumanReview, reason, signals, highRiskFiles);
    }

    private record ChangeRisk(long files, int churn, int sensitiveFiles, boolean summaryOnly) {
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

}
