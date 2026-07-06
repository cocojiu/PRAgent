package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.HighRiskReviewDto;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardHighRiskReviewAssemblerTest {

    private final DashboardHighRiskReviewAssembler assembler = new DashboardHighRiskReviewAssembler(
        new DashboardStatusMapper()
    );

    @Test
    void assemblesHighRiskReviewsForDashboardDisplay() {
        List<HighRiskReviewDto> result = assembler.assemble(List.of(
            highRiskReview("Fix auth bypass", "api", "CRITICAL", 4L, "COMPLETED", LocalDateTime.of(2026, 6, 17, 9, 30)),
            highRiskReview("Harden config", "ops", "HIGH", null, "FAILED", LocalDateTime.of(2026, 6, 16, 18, 15))
        ));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Fix auth bypass");
        assertThat(result.get(0).riskLevel()).isEqualTo("critical");
        assertThat(result.get(0).ruleHits()).isEqualTo(4L);
        assertThat(result.get(0).reviewedAt()).isEqualTo("2026-06-17 09:30");
        assertThat(result.get(0).status()).isEqualTo("\u5df2\u5b8c\u6210");
        assertThat(result.get(1).ruleHits()).isZero();
        assertThat(result.get(1).status()).isEqualTo("\u5931\u8d25");
    }

    @Test
    void handlesNullValuesAndSource() {
        List<HighRiskReviewDto> result = assembler.assemble(List.of(
            highRiskReview("Untitled", "api", null, null, null, null)
        ));

        assertThat(result.get(0).riskLevel()).isNull();
        assertThat(result.get(0).ruleHits()).isZero();
        assertThat(result.get(0).reviewedAt()).isEmpty();
        assertThat(assembler.assemble(null)).isEmpty();
    }

    private DashboardHighRiskReview highRiskReview(
        String title,
        String repository,
        String riskLevel,
        Long ruleHits,
        String status,
        LocalDateTime createdAt
    ) {
        DashboardHighRiskReview review = new DashboardHighRiskReview();
        review.setTitle(title);
        review.setRepository(repository);
        review.setRiskLevel(riskLevel);
        review.setRuleHits(ruleHits);
        review.setStatus(status);
        review.setCreatedAt(createdAt);
        return review;
    }
}
