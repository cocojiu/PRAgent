package com.repoguard.agent.review;
import com.repoguard.agent.dto.LlmUsageCostSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
class LlmReviewCostEstimator {

    LlmUsageCostSnapshot snapshot(ReviewPolicySettings settings, LlmCallResult usage, boolean partial) {
        Integer prompt = usage == null ? null : usage.promptTokens();
        Integer output = usage == null ? null : usage.completionTokens();
        Integer cached = usage == null ? null : usage.cachedInputTokens();
        boolean validCache = cached != null && prompt != null && cached >= 0 && cached <= prompt;
        Integer normal = validCache ? prompt - cached : null;
        BigDecimal inputPrice = settings == null ? null : settings.inputTokenPricePerMillion();
        BigDecimal outputPrice = settings == null ? null : settings.outputTokenPricePerMillion();
        BigDecimal cachePrice = settings == null ? null : settings.cachedInputTokenPricePerMillion();
        BigDecimal amount = null;
        String source;
        if (prompt == null || output == null || prompt < 0 || output < 0) {
            source = "UNKNOWN_USAGE";
        } else if (inputPrice == null || outputPrice == null || inputPrice.signum() <= 0 || outputPrice.signum() <= 0
            || (cachePrice != null && cachePrice.signum() < 0)) {
            source = "UNKNOWN_PRICING";
        } else {
            boolean split = validCache && cachePrice != null;
            BigDecimal input = split
                ? BigDecimal.valueOf(normal).multiply(inputPrice).add(BigDecimal.valueOf(cached).multiply(cachePrice))
                : BigDecimal.valueOf(prompt).multiply(settings.conservativeInputTokenPricePerMillion());
            amount = input.add(BigDecimal.valueOf(output).multiply(outputPrice))
                .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
            source = split ? "CACHE_SPLIT_ESTIMATE"
                : validCache ? "CONSERVATIVE_CACHE_PRICE_UNKNOWN" : "CONSERVATIVE_CACHE_USAGE_UNKNOWN";
        }
        return new LlmUsageCostSnapshot(validCache ? cached : null, normal,
            usage == null ? "UNKNOWN" : usage.usageSource(), source + (partial ? "_PARTIAL" : ""),
            pricingVersion(settings), "CNY", "REVIEW_POLICY_CONFIG", inputPrice, outputPrice, cachePrice, amount);
    }

    private String pricingVersion(ReviewPolicySettings settings) {
        if (settings == null) return "CNY_PER_MILLION_V1_UNKNOWN";
        String definition = String.join("|", "CNY_PER_MILLION_V1", String.valueOf(settings.llmProvider()),
            String.valueOf(settings.modelName()), decimal(settings.inputTokenPricePerMillion()),
            decimal(settings.outputTokenPricePerMillion()), decimal(settings.cachedInputTokenPricePerMillion()));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(definition.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "CNY_PER_MILLION_V1_" + java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.stripTrailingZeros().toPlainString();
    }

    BigDecimal estimate(ReviewPolicySettings settings, Integer promptTokens, Integer completionTokens) {
        if (settings == null || promptTokens == null || completionTokens == null
            || promptTokens < 0 || completionTokens < 0
            || settings.inputTokenPricePerMillion() == null
            || settings.outputTokenPricePerMillion() == null
            || settings.inputTokenPricePerMillion().signum() <= 0
            || settings.outputTokenPricePerMillion().signum() <= 0) {
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
