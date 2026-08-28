package com.repoguard.agent.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.review.pipeline")
public class ReviewPipelineBudgetProperties {

    /**
     * Total wall-clock budget for one LLM review. Must stay below the RabbitMQ
     * consumer timeout, which must stay below
     * {@code app.rabbit.review.review-execution-timeout-ms}; see
     * ReviewTimeoutLayeringValidator.
     */
    private long budgetMs = 480000;
    private int maxTotalChunks = 64;
    private int maxInFlightChunks = 3;

    public long getBudgetMs() {
        return budgetMs;
    }

    public void setBudgetMs(long budgetMs) {
        this.budgetMs = budgetMs;
    }

    public int getMaxTotalChunks() {
        return maxTotalChunks;
    }

    public void setMaxTotalChunks(int maxTotalChunks) {
        this.maxTotalChunks = maxTotalChunks;
    }

    public int getMaxInFlightChunks() {
        return maxInFlightChunks;
    }

    public void setMaxInFlightChunks(int maxInFlightChunks) {
        this.maxInFlightChunks = maxInFlightChunks;
    }
}
