package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared hard budget for every provider call made by one evaluation run.
 *
 * <p>Each call reserves a conservative upper bound before any outbound request is sent. The
 * reservation is reconciled with provider usage only after a successful response, which keeps
 * concurrent chunk and verification calls inside the run-level token and cost limits.</p>
 */
public final class LlmEvaluationBudget {

    private static final long MESSAGE_OVERHEAD_TOKENS = 1_024L;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private final long maxTokens;
    private final BigDecimal maxCost;
    private long accountedTokens;
    private BigDecimal accountedCost = BigDecimal.ZERO;

    public LlmEvaluationBudget(long maxTokens, BigDecimal maxCost) {
        if (maxTokens < 1 || maxCost == null || maxCost.signum() < 0) {
            throw new IllegalArgumentException("evaluation budget limits are invalid");
        }
        this.maxTokens = maxTokens;
        this.maxCost = maxCost;
    }

    public synchronized Reservation reserve(
        String systemPrompt,
        String userPrompt,
        Integer maxOutputTokens,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion
    ) {
        long inputUpperBound = saturatedAdd(
            utf8Length(systemPrompt),
            utf8Length(userPrompt),
            MESSAGE_OVERHEAD_TOKENS
        );
        long outputUpperBound = Math.max(0L, maxOutputTokens == null ? 0L : maxOutputTokens.longValue());
        long tokenUpperBound = saturatedAdd(inputUpperBound, outputUpperBound);
        BigDecimal costUpperBound = estimateCost(
            inputUpperBound,
            outputUpperBound,
            inputPricePerMillion,
            outputPricePerMillion
        );
        long nextTokens = saturatedAdd(accountedTokens, tokenUpperBound);
        BigDecimal nextCost = accountedCost.add(costUpperBound);
        if (nextTokens > maxTokens || nextCost.compareTo(maxCost) > 0) {
            throw new BudgetExceededException();
        }
        accountedTokens = nextTokens;
        accountedCost = nextCost;
        return new Reservation(this, tokenUpperBound, costUpperBound);
    }

    public synchronized long accountedTokens() {
        return accountedTokens;
    }

    public synchronized BigDecimal accountedCost() {
        return accountedCost;
    }

    <T> T execute(
        String systemPrompt,
        String userPrompt,
        Integer maxOutputTokens,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion,
        Supplier<T> request,
        Function<T, LlmCallResult> usageExtractor
    ) {
        try (Reservation reservation = reserve(
            systemPrompt,
            userPrompt,
            maxOutputTokens,
            inputPricePerMillion,
            outputPricePerMillion
        )) {
            T response = request.get();
            reservation.complete(usageExtractor.apply(response), inputPricePerMillion, outputPricePerMillion);
            return response;
        }
    }

    private synchronized void reconcile(
        long reservedTokens,
        BigDecimal reservedCost,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion
    ) {
        long actualPromptTokens = Math.max(0L, promptTokens == null ? 0L : promptTokens.longValue());
        long actualCompletionTokens = Math.max(0L, completionTokens == null ? 0L : completionTokens.longValue());
        long reportedTotal = Math.max(0L, totalTokens == null ? 0L : totalTokens.longValue());
        long actualTokens = Math.max(reportedTotal, saturatedAdd(actualPromptTokens, actualCompletionTokens));
        BigDecimal actualCost = estimateCost(
            actualPromptTokens,
            actualCompletionTokens,
            inputPricePerMillion,
            outputPricePerMillion
        );
        accountedTokens = Math.max(0L, saturatedAdd(accountedTokens - reservedTokens, actualTokens));
        accountedCost = accountedCost.subtract(reservedCost).add(actualCost).max(BigDecimal.ZERO);
        if (accountedTokens > maxTokens || accountedCost.compareTo(maxCost) > 0) {
            throw new BudgetExceededException();
        }
    }

    private static BigDecimal estimateCost(
        long inputTokens,
        long outputTokens,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion
    ) {
        BigDecimal inputPrice = nonNegative(inputPricePerMillion);
        BigDecimal outputPrice = nonNegative(outputPricePerMillion);
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens), MathContext.DECIMAL128)
            .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens), MathContext.DECIMAL128))
            .divide(ONE_MILLION, MathContext.DECIMAL128);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long saturatedAdd(long... values) {
        long result = 0L;
        for (long value : values) {
            if (value <= 0) {
                continue;
            }
            if (Long.MAX_VALUE - result < value) {
                return Long.MAX_VALUE;
            }
            result += value;
        }
        return result;
    }

    public static final class Reservation implements AutoCloseable {
        private final LlmEvaluationBudget budget;
        private final long reservedTokens;
        private final BigDecimal reservedCost;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Reservation(LlmEvaluationBudget budget, long reservedTokens, BigDecimal reservedCost) {
            this.budget = Objects.requireNonNull(budget, "budget");
            this.reservedTokens = reservedTokens;
            this.reservedCost = Objects.requireNonNull(reservedCost, "reservedCost");
        }

        public void complete(
            LlmCallResult result,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion
        ) {
            Objects.requireNonNull(result, "result");
            if (!completed.compareAndSet(false, true)) {
                throw new IllegalStateException("evaluation budget reservation is already completed");
            }
            if (result.promptTokens() == null
                && result.completionTokens() == null
                && result.totalTokens() == null) {
                return;
            }
            budget.reconcile(
                reservedTokens,
                reservedCost,
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                inputPricePerMillion,
                outputPricePerMillion
            );
        }

        /** Unknown provider usage remains conservatively reserved when a call fails. */
        @Override
        public void close() {
            // Intentionally retain incomplete reservations.
        }
    }

    public static final class BudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
