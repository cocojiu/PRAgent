package com.repoguard.agent.review;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PrReviewSummaryBuilder {

    public PrReviewSummaryDto build(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        PrRiskProfileDto riskProfile
    ) {
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        String overallRisk = riskProfile == null ? "info" : riskProfile.level();
        boolean humanReviewRequired = Boolean.TRUE.equals(task.humanReviewRequired())
            || Boolean.TRUE.equals(riskProfile == null ? false : riskProfile.recommendHumanReview());
        boolean recommendMerge = criticalCount == 0 && highCount == 0 && !humanReviewRequired;
        String mergeRecommendation = mergeRecommendation(
            recommendMerge,
            criticalCount,
            highCount,
            mediumCount,
            humanReviewRequired
        );
        List<String> keyRisks = buildKeyRisks(riskProfile, missingTests, criticalCount, highCount, mediumCount);
        List<String> focusFiles = buildFocusFiles(riskProfile, changedFiles);
        String summary = "本次 PR 综合风险为 " + riskText(overallRisk)
            + "，包含 " + changedFiles.size() + " 个变更文件、" + findings.size() + " 条审查发现"
            + (missingTests.isEmpty() ? "" : "、" + missingTests.size() + " 条缺失测试建议")
            + "。";
        String commentBody = buildCommentBody(
            task,
            overallRisk,
            summary,
            mergeRecommendation,
            keyRisks,
            focusFiles
        );
        return new PrReviewSummaryDto(
            overallRisk,
            summary,
            mergeRecommendation,
            recommendMerge,
            humanReviewRequired,
            keyRisks,
            focusFiles,
            commentBody
        );
    }

    private String mergeRecommendation(
        boolean recommendMerge,
        int criticalCount,
        int highCount,
        int mediumCount,
        boolean humanReviewRequired
    ) {
        if (criticalCount > 0 || highCount > 0) {
            return "暂不建议直接合并，请优先处理高风险发现后再评估。";
        }
        if (humanReviewRequired || mediumCount > 0) {
            return "建议完成必要人工复核和中风险确认后再合并。";
        }
        return recommendMerge ? "未发现阻塞性风险，可按团队流程合并。" : "建议完成复核后再合并。";
    }

    private List<String> buildKeyRisks(
        PrRiskProfileDto riskProfile,
        List<MissingTestDto> missingTests,
        int criticalCount,
        int highCount,
        int mediumCount
    ) {
        List<String> risks = new java.util.ArrayList<>();
        if (criticalCount > 0) {
            risks.add("包含 " + criticalCount + " 条严重风险发现");
        }
        if (highCount > 0) {
            risks.add("包含 " + highCount + " 条高风险发现");
        }
        if (mediumCount > 0) {
            risks.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (!missingTests.isEmpty()) {
            risks.add("存在 " + missingTests.size() + " 条缺失测试建议");
        }
        if (riskProfile != null && riskProfile.signals() != null) {
            riskProfile.signals().stream()
                .filter(StringUtils::hasText)
                .filter(signal -> risks.stream().noneMatch(existing -> existing.equals(signal)))
                .limit(Math.max(0, 5 - risks.size()))
                .forEach(risks::add);
        }
        if (risks.isEmpty()) {
            risks.add("未发现明显阻塞性风险");
        }
        return risks.stream().limit(5).toList();
    }

    private List<String> buildFocusFiles(PrRiskProfileDto riskProfile, List<ChangedFileDto> changedFiles) {
        List<String> files = new java.util.ArrayList<>();
        if (riskProfile != null && riskProfile.highRiskFiles() != null) {
            riskProfile.highRiskFiles().stream()
                .map(PrRiskFileDto::file)
                .filter(StringUtils::hasText)
                .limit(3)
                .forEach(files::add);
        }
        if (files.size() < 3) {
            changedFiles.stream()
                .sorted(Comparator.comparingInt(file -> -(safeInt(file.additions()) + safeInt(file.deletions()))))
                .map(ChangedFileDto::path)
                .filter(StringUtils::hasText)
                .filter(file -> !files.contains(file))
                .limit(3 - files.size())
                .forEach(files::add);
        }
        return files;
    }

    private String buildCommentBody(
        ReviewTaskListItem task,
        String overallRisk,
        String summary,
        String mergeRecommendation,
        List<String> keyRisks,
        List<String> focusFiles
    ) {
        StringBuilder body = new StringBuilder();
        body.append("## RepoGuard PR 总评");
        body.append("\n\n").append(summary);
        body.append("\n\n**合并建议**：").append(mergeRecommendation);
        body.append("\n\n**关键风险**");
        keyRisks.forEach(risk -> body.append("\n- ").append(risk));
        if (!focusFiles.isEmpty()) {
            body.append("\n\n**建议重点查看文件**");
            focusFiles.forEach(file -> body.append("\n- `").append(file).append("`"));
        }
        body.append("\n\n> 任务 #").append(task.id()).append("，风险等级：").append(riskText(overallRisk)).append("。");
        return body.toString();
    }

    private String riskText(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return "提示";
        }
        return switch (riskLevel.trim().toLowerCase(Locale.ROOT)) {
            case "critical" -> "严重";
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            default -> "提示";
        };
    }

    private int countSeverity(List<ReviewFindingDto> findings, String severity) {
        return (int) findings.stream().filter(finding -> severity.equalsIgnoreCase(finding.severity())).count();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
