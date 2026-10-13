package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LlmUsageBudgetMathTest {
    @Test
    void reservesUtf8MessageBytesAndOutputWithoutAssumingCacheHits() {
        var bound = LlmUsageBudgetMath.bound(settings(), "sys", "汉字", 100);
        assertThat(bound.tokens()).isEqualTo(1133);
        assertThat(bound.cost()).isEqualByComparingTo("0.002866");
        var actual = new LlmCallResult("", 100, 10, 110, LlmStructuredOutputStatus.NOT_REQUESTED,
            80, "OPENAI_COMPATIBLE_CACHE_DETAILS");
        var reconciled = LlmUsageBudgetMath.reconcile(bound, settings(), actual);
        assertThat(reconciled.tokens()).isEqualTo(110);
        assertThat(reconciled.cost()).isEqualByComparingTo("0.000160");
    }

    @Test
    void totalOnlyAndInconsistentUsageKeepConservativeReservationsAndPriceReportedExcess() {
        var bound = LlmUsageBudgetMath.bound(settings(), "", "", 100);
        var totalOnly = LlmUsageBudgetMath.reconcile(bound, settings(), new LlmCallResult("", null, null, 2000));
        assertThat(totalOnly.tokens()).isEqualTo(2000);
        assertThat(totalOnly.cost()).isEqualByComparingTo("0.009856");
        var incomplete = LlmUsageBudgetMath.reconcile(bound, settings(), new LlmCallResult("", 100, null, 100));
        assertThat(incomplete).isEqualTo(bound);
        assertThat(LlmUsageBudgetMath.complete(new LlmCallResult("", -1, 10, 9))).isFalse();
        assertThat(LlmUsageBudgetMath.complete(new LlmCallResult("", 10, 10, 30))).isFalse();
        assertThat(LlmUsageBudgetMath.reconcile(bound, settings(), new LlmCallResult("", 10, 10, -1)))
            .isEqualTo(bound);
    }

    @Test
    void completeZeroUsageIsKnownAndMissingPricingRemainsUnknown() {
        var bound = LlmUsageBudgetMath.bound(settings(), "", "", 100);
        var zero = LlmUsageBudgetMath.reconcile(bound, settings(), new LlmCallResult("", 0, 0, 0));
        assertThat(zero.tokens()).isZero();
        assertThat(zero.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        var priced = settings();
        var unknown = new ReviewPolicySettings(priced.exists(), true, priced.llmProvider(), priced.modelName(),
            priced.baseUrl(), priced.apiKey(), 10, BigDecimal.ZERO, 100, true, 1, 5, 100, 5, 100,
            BigDecimal.ZERO, BigDecimal.ZERO, priced.strategyRelease(), null);
        assertThat(LlmUsageBudgetMath.bound(unknown, "", "", 100).cost()).isNull();
        assertThat(LlmUsageBudgetMath.add(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    }

    static ReviewPolicySettings settings() {
        return new ReviewPolicySettings(true, true, "fixture", "fixture", "https://fixture.invalid", "",
            10, BigDecimal.ZERO, 100, true, 1, 5, 100, 5, 100,
            new BigDecimal("2"), new BigDecimal("8"), ReviewStrategyRelease.observeDefaults(), new BigDecimal("0.5"));
    }
}
