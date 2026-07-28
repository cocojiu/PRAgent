package com.repoguard.agent.config;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.external.ExternalCallResilienceProperties;
import com.repoguard.agent.review.ReviewPipelineBudgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Enforces the review timeout layering so each layer only backstops the one
 * below it:
 *
 * <pre>
 * pipeline budget  &lt;  RabbitMQ consumer_timeout  &lt;  recovery staleness threshold
 * </pre>
 *
 * <p>When these collapse onto the same instant, RabbitMQ redelivers a task at the
 * same moment the recovery compensator decides it is stale, and both paths race to
 * re-run it. The claim guard keeps the data correct but the LLM spend is wasted.
 *
 * <p>Only the two application-side values can be checked here; {@code
 * consumer_timeout} lives in the broker (see {@code config/rabbitmq/rabbitmq.conf})
 * and is verified as part of the deployment checks.
 */
@Component
public class ReviewTimeoutLayeringValidator implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTimeoutLayeringValidator.class);

    private final long pipelineBudgetMs;
    private final long recoveryStalenessMs;
    private final int maxTotalChunks;
    private final int maxInFlightChunks;
    private final int llmChunkThreads;
    private final int llmChunkQueueCapacity;
    private final int llmBulkheadPermits;
    private final int llmBulkheadWaitMs;

    public ReviewTimeoutLayeringValidator(
        ReviewPipelineBudgetProperties budgetProperties,
        RabbitReviewQueueProperties reviewQueueProperties,
        AsyncExecutorProperties asyncExecutorProperties,
        ExternalCallResilienceProperties resilienceProperties
    ) {
        this.pipelineBudgetMs = budgetProperties.getBudgetMs();
        this.recoveryStalenessMs = reviewQueueProperties.getReviewExecutionTimeoutMs();
        this.maxTotalChunks = budgetProperties.getMaxTotalChunks();
        this.maxInFlightChunks = budgetProperties.getMaxInFlightChunks();
        this.llmChunkThreads = asyncExecutorProperties.getLlmChunkThreads();
        this.llmChunkQueueCapacity = asyncExecutorProperties.getLlmChunkQueueCapacity();
        this.llmBulkheadPermits = resilienceProperties.getLlm().getBulkheadMaxConcurrentCalls();
        this.llmBulkheadWaitMs = resilienceProperties.getLlm().getBulkheadMaxWaitMillis();
    }

    @Override
    public void afterPropertiesSet() {
        if (pipelineBudgetMs <= 0) {
            throw new IllegalStateException(
                "repoguard.review.pipeline.budget-ms must be positive but was " + pipelineBudgetMs
            );
        }
        if (pipelineBudgetMs >= recoveryStalenessMs) {
            throw new IllegalStateException(
                "repoguard.review.pipeline.budget-ms=" + pipelineBudgetMs
                    + " must stay below app.rabbit.review.review-execution-timeout-ms="
                    + recoveryStalenessMs
                    + " so the recovery compensator only backstops a review the budget already gave up on"
            );
        }
        requirePositive(maxTotalChunks, "repoguard.review.pipeline.max-total-chunks");
        requirePositive(maxInFlightChunks, "repoguard.review.pipeline.max-in-flight-chunks");
        requirePositive(llmChunkThreads, "repoguard.async.llm-chunk-threads");
        requirePositive(llmChunkQueueCapacity, "repoguard.async.llm-chunk-queue-capacity");
        requirePositive(llmBulkheadPermits, "app.external-call.resilience.llm.bulkhead-max-concurrent-calls");
        requirePositive(llmBulkheadWaitMs, "app.external-call.resilience.llm.bulkhead-max-wait-millis");
        if (maxInFlightChunks > llmChunkThreads) {
            throw new IllegalStateException(
                "repoguard.review.pipeline.max-in-flight-chunks=" + maxInFlightChunks
                    + " must not exceed repoguard.async.llm-chunk-threads=" + llmChunkThreads
            );
        }
        if (llmChunkThreads >= llmBulkheadPermits) {
            throw new IllegalStateException(
                "repoguard.async.llm-chunk-threads=" + llmChunkThreads
                    + " must stay below app.external-call.resilience.llm.bulkhead-max-concurrent-calls="
                    + llmBulkheadPermits
                    + " so non-chunk LLM calls retain a permit"
            );
        }
        LOGGER.info(
            "Review capacity layering validated pipelineBudgetMs={} recoveryStalenessMs={} "
                + "maxTotalChunks={} maxInFlightChunks={} llmChunkThreads={} llmBulkheadPermits={}; "
                + "keep the broker consumer_timeout between them",
            pipelineBudgetMs,
            recoveryStalenessMs,
            maxTotalChunks,
            maxInFlightChunks,
            llmChunkThreads,
            llmBulkheadPermits
        );
    }

    private void requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalStateException(property + " must be positive but was " + value);
        }
    }
}
