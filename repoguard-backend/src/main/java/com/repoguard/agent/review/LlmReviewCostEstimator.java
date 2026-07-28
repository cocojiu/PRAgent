package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
class LlmReviewCostEstimator {

    BigDecimal estimate(ReviewPolicySettings settings, Integer promptTokens, Integer completionTokens) {
        if (settings == null || promptTokens == null && completionTokens == null) {
            return null;
        }
        BigDecimal inputCost = BigDecimal.valueOf(safeInt(promptTokens))
            .multiply(price(settings.inputTokenPricePerMillion()));
        BigDecimal outputCost = BigDecimal.valueOf(safeInt(completionTokens))
            .multiply(price(settings.outputTokenPricePerMillion()));
        BigDecimal total = inputCost.add(outputCost).divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        return total.compareTo(BigDecimal.ZERO) == 0 ? null : total;
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
