package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.external.ExternalCallResilienceProperties;
import com.repoguard.agent.review.ReviewPipelineBudgetProperties;
import org.junit.jupiter.api.Test;

class ReviewTimeoutLayeringValidatorTest {

    @Test
    void acceptsBoundedDefaultCapacityAndTimeoutLayering() {
        assertThatCode(() -> validator(
            new ReviewPipelineBudgetProperties(),
            new RabbitReviewQueueProperties(),
            new AsyncExecutorProperties(),
            new ExternalCallResilienceProperties()
        ).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsInFlightWindowLargerThanExecutor() {
        ReviewPipelineBudgetProperties pipeline = new ReviewPipelineBudgetProperties();
        pipeline.setMaxInFlightChunks(4);

        assertThatThrownBy(() -> validator(
            pipeline,
            new RabbitReviewQueueProperties(),
            new AsyncExecutorProperties(),
            new ExternalCallResilienceProperties()
        ).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-in-flight-chunks=4", "llm-chunk-threads=3");
    }

    @Test
    void rejectsExecutorWithoutSpareBulkheadPermit() {
        AsyncExecutorProperties async = new AsyncExecutorProperties();
        async.setLlmChunkThreads(4);

        assertThatThrownBy(() -> validator(
            new ReviewPipelineBudgetProperties(),
            new RabbitReviewQueueProperties(),
            async,
            new ExternalCallResilienceProperties()
        ).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("llm-chunk-threads=4", "bulkhead-max-concurrent-calls=4");
    }

    @Test
    void rejectsZeroBulkheadWait() {
        ExternalCallResilienceProperties resilience = new ExternalCallResilienceProperties();
        resilience.getLlm().setBulkheadMaxWaitMillis(0);

        assertThatThrownBy(() -> validator(
            new ReviewPipelineBudgetProperties(),
            new RabbitReviewQueueProperties(),
            new AsyncExecutorProperties(),
            resilience
        ).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bulkhead-max-wait-millis must be positive");
    }

    @Test
    void rejectsPipelineBudgetAtRecoveryThreshold() {
        ReviewPipelineBudgetProperties pipeline = new ReviewPipelineBudgetProperties();
        pipeline.setBudgetMs(1_800_000);

        assertThatThrownBy(() -> validator(
            pipeline,
            new RabbitReviewQueueProperties(),
            new AsyncExecutorProperties(),
            new ExternalCallResilienceProperties()
        ).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must stay below");
    }

    private ReviewTimeoutLayeringValidator validator(
        ReviewPipelineBudgetProperties pipeline,
        RabbitReviewQueueProperties rabbit,
        AsyncExecutorProperties async,
        ExternalCallResilienceProperties resilience
    ) {
        return new ReviewTimeoutLayeringValidator(pipeline, rabbit, async, resilience);
    }
}
