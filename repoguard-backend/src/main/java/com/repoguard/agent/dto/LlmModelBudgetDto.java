package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.YearMonth;

public record LlmModelBudgetDto(
    YearMonth month,
    long tokenBudget,
    long tokenUsed,
    long tokenRemaining,
    BigDecimal costBudget,
    BigDecimal costUsed,
    BigDecimal costRemaining,
    boolean exhausted,
    long unpricedCalls,
    long legacyUnknownAttempts
) {
    public LlmModelBudgetDto(YearMonth month, long tokenBudget, long tokenUsed, long tokenRemaining,
        BigDecimal costBudget, BigDecimal costUsed, BigDecimal costRemaining, boolean exhausted) {
        this(month, tokenBudget, tokenUsed, tokenRemaining, costBudget, costUsed, costRemaining, exhausted, 0, 0);
    }
}
