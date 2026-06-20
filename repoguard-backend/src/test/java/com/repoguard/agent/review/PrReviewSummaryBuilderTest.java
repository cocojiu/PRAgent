package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrReviewSummaryBuilderTest {

    private final PrReviewSummaryBuilder builder = new PrReviewSummaryBuilder();

    @Test
    void blocksMergeAndPrioritizesRiskFilesForHighRiskFindings() {
        PrRiskProfileDto riskProfile = new PrRiskProfileDto(
            72,
            "high",
            "high risk",
            true,
            "manual review",
            List.of("触及 2 个敏感文件"),
            List.of(new PrRiskFileDto("src/SecurityConfig.java", "modified", 20, 4, 1, 80, List.of("认证或权限")))
        );

        var result = builder.build(
            task(false),
            List.of(
                finding("critical", "db/migration/V25__security.sql"),
                finding("high", "src/SecurityConfig.java")
            ),
            List.of(new MissingTestDto("src/SecurityConfig.java", "authorize", "unit", "add test")),
            List.of(
                new ChangedFileDto("db/migration/V25__security.sql", "modified", 100, 50),
                new ChangedFileDto("src/SecurityConfig.java", "modified", 20, 4)
            ),
            riskProfile
        );

        assertThat(result.overallRisk()).isEqualTo("high");
        assertThat(result.recommendMerge()).isFalse();
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(result.mergeRecommendation()).contains("暂不建议直接合并");
        assertThat(result.keyRisks()).contains(
            "包含 1 条严重风险发现",
            "包含 1 条高风险发现",
            "存在 1 条缺失测试建议"
        );
        assertThat(result.focusFiles()).containsExactly(
            "src/SecurityConfig.java",
            "db/migration/V25__security.sql"
        );
        assertThat(result.githubCommentBody()).contains(
            "## RepoGuard PR 总评",
            "**合并建议**",
            "风险等级：高"
        );
    }

    @Test
    void requiresReviewBeforeMergeForMediumRisk() {
        PrRiskProfileDto riskProfile = new PrRiskProfileDto(
            35,
            "medium",
            "medium risk",
            false,
            "normal flow",
            List.of("包含 1 条中风险发现"),
            List.of()
        );

        var result = builder.build(
            task(false),
            List.of(finding("medium", "src/Service.java")),
            List.of(),
            List.of(new ChangedFileDto("src/Service.java", "modified", 10, 2)),
            riskProfile
        );

        assertThat(result.recommendMerge()).isTrue();
        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(result.mergeRecommendation()).contains("中风险确认后再合并");
        assertThat(result.keyRisks()).containsExactly("包含 1 条中风险发现");
    }

    @Test
    void recommendsMergeWhenNoBlockingRiskExists() {
        var result = builder.build(
            task(false),
            List.of(),
            List.of(),
            List.of(
                new ChangedFileDto("README.md", "modified", 4, 1),
                new ChangedFileDto("docs/guide.md", "modified", 2, 0)
            ),
            null
        );

        assertThat(result.overallRisk()).isEqualTo("info");
        assertThat(result.recommendMerge()).isTrue();
        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(result.mergeRecommendation()).isEqualTo("未发现阻塞性风险，可按团队流程合并。");
        assertThat(result.keyRisks()).containsExactly("未发现明显阻塞性风险");
        assertThat(result.focusFiles()).containsExactly("README.md", "docs/guide.md");
        assertThat(result.summary()).contains("2 个变更文件、0 条审查发现");
        assertThat(result.githubCommentBody()).contains("风险等级：提示");
    }

    private ReviewFindingDto finding(String severity, String file) {
        return new ReviewFindingDto(severity, file, 10, "message", "recommendation");
    }

    private ReviewTaskListItem task(boolean humanReviewRequired) {
        return new ReviewTaskListItem(
            521L,
            42,
            "PR summary",
            "repo",
            "org",
            "abc123",
            "main",
            "completed",
            "info",
            0,
            "completed",
            "github",
            "github",
            "2026-06-18 10:00:00",
            "1 分 0 秒",
            null,
            null,
            null,
            humanReviewRequired,
            humanReviewRequired ? "pending" : "not_required",
            null,
            null,
            null
        );
    }
}
