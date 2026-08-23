package com.repoguard.agent.review.execution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.execution")
public class ReviewExecutionBudgetProperties {

    @Min(1_000)
    @Max(1_200_000)
    private long budgetMs = 600_000;

    @Min(1_000)
    @Max(120_000)
    private long persistenceReserveMs = 30_000;

    @Min(0)
    @Max(30_000)
    private long terminalPersistenceGraceMs = 5_000;

    public long getBudgetMs() {
        return budgetMs;
    }

    public void setBudgetMs(long budgetMs) {
        this.budgetMs = budgetMs;
    }

    public long getPersistenceReserveMs() { return persistenceReserveMs; }
    public void setPersistenceReserveMs(long persistenceReserveMs) { this.persistenceReserveMs = persistenceReserveMs; }
    public long getTerminalPersistenceGraceMs() { return terminalPersistenceGraceMs; }
    public void setTerminalPersistenceGraceMs(long terminalPersistenceGraceMs) {
        this.terminalPersistenceGraceMs = terminalPersistenceGraceMs;
    }
}
