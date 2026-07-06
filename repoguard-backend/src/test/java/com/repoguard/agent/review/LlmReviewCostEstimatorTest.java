package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewPolicySettings;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LlmReviewCostEstimatorTest {

    private final LlmReviewCostEstimator estimator = new LlmReviewCostEstimator();

    @Test
    void returnsNullWhenSettingsOrTokenUsageIsMissing() {
        assertThat(estimator.estimate(null, 100, 20)).isNull();
        assertThat(estimator.estimate(settings(BigDecimal.ONE, BigDecimal.ONE), null, null)).isNull();
    }

    @Test
    void returnsNullWhenConfiguredPricesAreMissingOrZero() {
        assertThat(estimator.estimate(settings(null, null), 100, 20)).isNull();
        assertThat(estimator.estimate(settings(BigDecimal.ZERO, BigDecimal.ZERO), 100, 20)).isNull();
    }

    @Test
    void estimatesInputAndOutputTokenCostPerMillion() {
        BigDecimal cost = estimator.estimate(
            settings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)),
            100,
            20
        );

        assertThat(cost).isEqualByComparingTo("0.000080");
    }

    @Test
    void estimatesCostWhenOnlyOneTokenSideIsPresent() {
        assertThat(estimator.estimate(settings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)), 100, null))
            .isEqualByComparingTo("0.000050");
        assertThat(estimator.estimate(settings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)), null, 20))
            .isEqualByComparingTo("0.000030");
    }

    @Test
    void roundsToSixDecimalPlaces() {
        BigDecimal cost = estimator.estimate(
            settings(BigDecimal.ONE, BigDecimal.ONE),
            1,
            0
        );

        assertThat(cost).isEqualByComparingTo("0.000001");
    }

    private ReviewPolicySettings settings(BigDecimal inputPrice, BigDecimal outputPrice) {
        return new ReviewPolicySettings(
            true,
            true,
            "openai",
            "gpt-test",
            "https://llm.example.test",
            "llm-key",
            30,
            BigDecimal.valueOf(0.2),
            1024,
            true,
            1,
            6,
            700,
            4,
            450,
            inputPrice,
            outputPrice
        );
    }
}
