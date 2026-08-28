package com.repoguard.agent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewQualityGroupDtoTest {

    @Test
    void mapsEveryQualityBaselineContractField() {
        ReviewQualityGroupBaseline baseline = new ReviewQualityGroupBaseline(
            "R001",
            "RULE",
            "owner/repo",
            "java",
            "HIGH",
            "versions",
            "detector-v2",
            2L,
            3L,
            "prompt-v2",
            "context-v2",
            "schema-v2",
            "verifier-v1",
            "aggregation-v1",
            100L,
            80L,
            new BigDecimal("0.80"),
            70L,
            10L,
            20L,
            new BigDecimal("0.875"),
            new BigDecimal("0.125"),
            40L,
            new BigDecimal("0.40"),
            5L,
            new BigDecimal("0.05"),
            1L,
            60L,
            new BigDecimal("0.60"),
            4L,
            new BigDecimal("0.04"),
            "WARN",
            List.of("coverage-low")
        );

        ReviewQualityGroupDto dto = ReviewQualityGroupDto.from(baseline);

        assertThat(dto).usingRecursiveComparison().isEqualTo(baseline);
    }
}
