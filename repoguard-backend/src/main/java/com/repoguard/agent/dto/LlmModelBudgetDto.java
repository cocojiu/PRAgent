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
    boolean exhausted
) {
}
