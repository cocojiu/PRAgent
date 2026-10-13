package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/** Conservative accounting bounds; cached input is never assumed before a response. */
final class LlmUsageBudgetMath {
    private LlmUsageBudgetMath() {
    }

    static Bound bound(ReviewPolicySettings settings, String system, String user, int outputLimit) {
        return bound(settings, add(bytes(system), bytes(user)), outputLimit);
    }

    static Bound bound(ReviewPolicySettings settings, long requestBytes, int outputLimit) {
        // UTF-8 bytes include the serialized schema and message envelope when called by transport.
        // Keep the same conservative envelope allowance as evaluation admission.
        long input = add(requestBytes, 1024);
        long tokens = add(input, outputLimit);
        BigDecimal inputPrice = settings.conservativeInputTokenPricePerMillion();
        BigDecimal outputPrice = settings.outputTokenPricePerMillion();
        return new Bound(tokens, known(inputPrice) && known(outputPrice)
            && (settings.cachedInputTokenPricePerMillion() == null || settings.cachedInputTokenPricePerMillion().signum() >= 0)
            ? amount(input, outputLimit, inputPrice, outputPrice) : null);
    }

    static Bound reconcile(Bound reserved, ReviewPolicySettings settings, LlmCallResult usage) {
        long prompt = usage == null || usage.promptTokens() == null ? -1 : usage.promptTokens();
        long output = usage == null || usage.completionTokens() == null ? -1 : usage.completionTokens();
        long total = usage == null || usage.totalTokens() == null ? -1 : usage.totalTokens();
        long split = add(Math.max(prompt, 0), Math.max(output, 0));
        if (complete(usage)) {
            return new Bound(split, split == 0 ? BigDecimal.ZERO
                : new LlmReviewCostEstimator().snapshot(settings, usage, false).estimatedAmount());
        }
        long accounted = Math.max(reserved.tokens(), Math.max(total, split));
        BigDecimal cost = reserved.cost();
        if (cost != null && accounted > reserved.tokens()) {
            BigDecimal worst = settings.conservativeInputTokenPricePerMillion().max(settings.outputTokenPricePerMillion());
            cost = cost.add(amount(accounted - reserved.tokens(), 0, worst, worst));
        }
        return new Bound(accounted, cost);
    }

    static boolean complete(LlmCallResult usage) {
        return usage != null && usage.promptTokens() != null && usage.completionTokens() != null
            && usage.promptTokens() >= 0 && usage.completionTokens() >= 0
            && (usage.totalTokens() == null || (long) usage.promptTokens() + usage.completionTokens() == usage.totalTokens());
    }

    static long add(long... values) {
        long result = 0;
        for (long value : values) {
            if (value < 0 || Long.MAX_VALUE - result < value) {
                return Long.MAX_VALUE;
            }
            result += value;
        }
        return result;
    }

    private static boolean known(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    private static BigDecimal amount(long input, long output, BigDecimal inputPrice, BigDecimal outputPrice) {
        return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output)))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.CEILING);
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    record Bound(long tokens, BigDecimal cost) {
    }
}
