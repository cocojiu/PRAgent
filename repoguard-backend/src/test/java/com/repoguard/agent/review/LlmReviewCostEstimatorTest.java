package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewPolicySettings;
import java.math.BigDecimal;
import com.repoguard.agent.dto.LlmUsageCostSnapshot;
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
    void doesNotPresentPartialUsageAsCompleteCost() {
        assertThat(estimator.estimate(settings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)), 100, null))
            .isNull();
        assertThat(estimator.estimate(settings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)), null, 20))
            .isNull();
        assertThat(estimator.estimate(settings(BigDecimal.ONE, BigDecimal.ZERO), 100, 20)).isNull();
        assertThat(estimator.estimate(settings(BigDecimal.ONE, BigDecimal.ONE), -1, 20)).isNull();
    }

    @Test
    void estimatesCnyStandardInputAndOutputPrices() {
        assertThat(estimator.estimate(settings(new BigDecimal("2"), new BigDecimal("8")), 1000, 200))
            .isEqualByComparingTo("0.003600");
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

    @Test
    void snapshotsCacheSplitAndExplicitFreeCachePrice() {
        var settings = withCachePrice(new BigDecimal("0.2"));
        var snapshot = estimator.snapshot(settings, usage(1000, 200, 600), false);
        assertThat(snapshot.normalInputTokens()).isEqualTo(400);
        assertThat(snapshot.cachedInputTokens()).isEqualTo(600);
        assertThat(snapshot.estimatedAmount()).isEqualByComparingTo("0.002520");
        assertThat(snapshot.costSource()).isEqualTo("CACHE_SPLIT_ESTIMATE");
        assertThat(snapshot.currency()).isEqualTo("CNY");
        assertThat(snapshot.priceSource()).isEqualTo("REVIEW_POLICY_CONFIG");
        var free = estimator.snapshot(withCachePrice(BigDecimal.ZERO), usage(1000, 0, 1000), false);
        assertThat(free.estimatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(free.normalInputTokens()).isZero();
        assertThat(estimator.snapshot(settings, usage(1000, 200, 0), false).estimatedAmount())
            .isEqualByComparingTo("0.003600");
    }

    @Test
    void missingOrInvalidCacheDetailsUseConservativeInputAndDoNotDiscountBudget() {
        var unknownPrice = estimator.snapshot(withCachePrice(null), usage(1000, 200, 600), false);
        assertThat(unknownPrice.estimatedAmount()).isEqualByComparingTo("0.003600");
        assertThat(unknownPrice.costSource()).isEqualTo("CONSERVATIVE_CACHE_PRICE_UNKNOWN");
        for (Integer cached : new Integer[] {null, -1, 1001}) {
            var snapshot = estimator.snapshot(withCachePrice(new BigDecimal("3")), usage(1000, 200, cached), false);
            assertThat(snapshot.cachedInputTokens()).isNull();
            assertThat(snapshot.normalInputTokens()).isNull();
            assertThat(snapshot.estimatedAmount()).isEqualByComparingTo("0.004600");
        }
        assertThat(withCachePrice(new BigDecimal("0.2")).conservativeInputTokenPricePerMillion())
            .isEqualByComparingTo("2");
        assertThat(estimator.snapshot(withCachePrice(null), usage(null, 200, null), false).estimatedAmount()).isNull();
        assertThat(estimator.snapshot(settings(BigDecimal.ZERO, BigDecimal.ONE), usage(100, 10, 0), false)
            .costSource()).isEqualTo("UNKNOWN_PRICING");
    }

    @Test
    void combinedCallsPreserveUnknownCacheAndMissingUsageAndPreventOverflow() {
        var combined = LlmCallResult.combine(usage(100, 10, 60), usage(50, 5, 0));
        assertThat(combined.promptTokens()).isEqualTo(150);
        assertThat(combined.cachedInputTokens()).isEqualTo(60);
        assertThat(LlmCallResult.combine(combined, usage(100, 10, null)).cachedInputTokens()).isNull();
        assertThat(LlmCallResult.combine(combined, usage(null, 10, null)).promptTokens()).isNull();
        assertThat(LlmCallResult.combine(usage(Integer.MAX_VALUE, 0, 0), usage(1, 0, 0)).promptTokens()).isNull();
    }

    @Test
    void persistedSnapshotKeepsOriginalPricesWhenSettingsChange() {
        var original = estimator.snapshot(withCachePrice(new BigDecimal("0.2")), usage(1000, 200, 600), true);
        var mapper = new com.repoguard.agent.config.JacksonConfig().objectMapper();
        var decoded = LlmUsageCostSnapshot.fromJson(original.toJson(mapper), mapper);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.costSource()).endsWith("_PARTIAL");
        assertThat(estimator.snapshot(withCachePrice(BigDecimal.ONE), usage(1000, 200, 600), false).pricingVersion())
            .isNotEqualTo(decoded.pricingVersion());
        assertThat(decoded.estimatedAmount()).isEqualByComparingTo("0.002520");
        assertThat(LlmUsageCostSnapshot.fromJson(null, mapper)).isNull();
        assertThat(LlmUsageCostSnapshot.fromJson(original.toJson(mapper).replaceFirst("\\{", "{\"futureField\":true,"), mapper))
            .isEqualTo(original);
    }

    private LlmCallResult usage(Integer prompt, Integer output, Integer cached) {
        return new LlmCallResult("", prompt, output, null, LlmStructuredOutputStatus.NOT_REQUESTED,
            cached, "OPENAI_COMPATIBLE_CACHE_DETAILS");
    }

    private ReviewPolicySettings withCachePrice(BigDecimal cachedPrice) {
        var base = settings(new BigDecimal("2"), new BigDecimal("8"));
        return new ReviewPolicySettings(base.exists(), base.llmEnabled(), base.llmProvider(), base.modelName(),
            base.baseUrl(), base.apiKey(), base.timeoutSeconds(), base.temperature(), base.maxTokens(), base.fallbackToRules(),
            base.workerConcurrency(), base.chunkFileThreshold(), base.chunkLineThreshold(), base.chunkMaxFiles(), base.chunkMaxLines(),
            base.inputTokenPricePerMillion(), base.outputTokenPricePerMillion(), base.strategyRelease(), cachedPrice);
    }
}
