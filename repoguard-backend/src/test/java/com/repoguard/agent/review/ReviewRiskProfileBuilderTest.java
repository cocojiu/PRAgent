package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRiskProfileBuilderTest {

    private final ReviewRiskProfileBuilder builder = new ReviewRiskProfileBuilder();

    @Test
    void buildsCriticalProfileFromFindingsChurnAndSensitiveFiles() {
        var result = builder.build(
            task("info", false),
            List.of(
                finding("critical", "db/migration/V25__security.sql"),
                finding("high", "src/main/java/AuthConfig.java")
            ),
            List.of(
                new ChangedFileDto("db/migration/V25__security.sql", "modified", 100, 50),
                new ChangedFileDto("src/main/java/AuthConfig.java", "modified", 20, 10)
            )
        );

        assertThat(result.score()).isEqualTo(83);
        assertThat(result.level()).isEqualTo("critical");
        assertThat(result.recommendHumanReview()).isTrue();
        assertThat(result.signals()).contains(
            "包含 2 条高危以上发现",
            "触及 2 个敏感文件"
        );
        assertThat(result.summary()).contains("2 个变更文件", "180 行增删", "2 条");
        assertThat(result.highRiskFiles()).hasSize(2);
        assertThat(result.highRiskFiles().getFirst().file()).isEqualTo("db/migration/V25__security.sql");
        assertThat(result.highRiskFiles().getFirst().reasons()).contains("数据库迁移", "认证或权限");
    }

    @Test
    void preservesStoredRiskLevelWithoutInflatingNumericScore() {
        var result = builder.build(
            task("high", false),
            List.of(),
            List.of(new ChangedFileDto("README.md", "modified", 1, 0))
        );

        assertThat(result.score()).isEqualTo(2);
        assertThat(result.level()).isEqualTo("high");
        assertThat(result.recommendHumanReview()).isFalse();
        assertThat(result.signals()).containsExactly("未发现明显放大风险的变更信号");
    }

    @Test
    void honorsTaskHumanReviewRequirementForLowRiskChanges() {
        var result = builder.build(task("info", true), List.of(), List.of());

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("info");
        assertThat(result.recommendHumanReview()).isTrue();
        assertThat(result.humanReviewReason()).contains("建议人工复核");
        assertThat(result.highRiskFiles()).isEmpty();
    }

    private ReviewFindingDto finding(String severity, String file) {
        return new ReviewFindingDto(severity, file, 10, "message", "recommendation");
    }

    private ReviewTaskListItem task(String riskLevel, boolean humanReviewRequired) {
        return new ReviewTaskListItem(
            521L,
            42,
            "Review risk profile",
            "repo",
            "org",
            "abc123",
            "main",
            "completed",
            riskLevel,
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
