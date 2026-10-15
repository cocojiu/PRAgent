package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReviewPolicySettingsCopyTest {
    @Test
    void namedCopiesKeepCredentialsChunkLimitsPricesAndStrategy() {
        var original = new ReviewPolicySettings(true, true,
            new ReviewPolicySettings.Connection("provider", "model", "https://example.invalid", "local-test", 21),
            new ReviewPolicySettings.Generation(new BigDecimal("0.3"), 512), false,
            new ReviewPolicySettings.ChunkPolicy(2, 3, 4, 5, 6),
            new ReviewPolicySettings.Pricing(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("3")),
            ReviewStrategyRelease.observeDefaults());
        var routed = original.withModel("candidate");
        assertThat(routed.modelName()).isEqualTo("candidate");
        assertThat(routed.apiKey()).isEqualTo(original.apiKey());
        assertThat(routed.chunkPolicy()).isSameAs(original.chunkPolicy());
        assertThat(routed.pricing()).isSameAs(original.pricing());
        assertThat(routed.strategyRelease()).isSameAs(original.strategyRelease());
        var disabled = routed.withoutLlm();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.fallbackToRules()).isTrue();
        assertThat(disabled.conservativeInputTokenPricePerMillion()).isEqualByComparingTo("3");
        assertThat(disabled.withExecution(true, 128, false).maxTokens()).isEqualTo(128);
        assertThat(original.maxTokens()).isEqualTo(512);
        assertThat(original.toString()).doesNotContain("local-test");
    }
}
