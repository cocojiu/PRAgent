package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmChunkReviewSchedulerTest {

    @Test
    void constructorRejectsMissingExecutorAndInvalidCapacityLimits() {
        assertThatThrownBy(() -> new LlmChunkReviewScheduler(null, 4, 2))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("chunkExecutor");
        assertThatThrownBy(() -> new LlmChunkReviewScheduler(Runnable::run, 0, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxTotalChunks must be positive");
        assertThatThrownBy(() -> new LlmChunkReviewScheduler(Runnable::run, 4, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxInFlightChunks must be positive");
    }

    @Test
    void capsTotalReviewsAndReturnsOutcomesInOriginalChunkOrder() {
        LlmChunkReviewScheduler scheduler = new LlmChunkReviewScheduler(Runnable::run, 2, 2);
        AtomicInteger reviewCalls = new AtomicInteger();
        Queue<String> fallbackCategories = new ConcurrentLinkedQueue<>();

        List<String> outcomes = scheduler.schedule(
            chunks(4),
            budget(Duration.ofSeconds(1)),
            chunk -> {
                reviewCalls.incrementAndGet();
                return "reviewed-" + chunk.index();
            },
            (chunk, category, failure) -> {
                fallbackCategories.add(category);
                return category + "-" + chunk.index();
            }
        );

        assertThat(reviewCalls.get()).isEqualTo(2);
        assertThat(outcomes).containsExactly(
            "reviewed-1",
            "reviewed-2",
            "chunk_limit_exceeded-3",
            "chunk_limit_exceeded-4"
        );
        assertThat(fallbackCategories).containsExactly(
            LlmChunkReviewScheduler.CHUNK_LIMIT_EXCEEDED_CATEGORY,
            LlmChunkReviewScheduler.CHUNK_LIMIT_EXCEEDED_CATEGORY
        );
    }

    @Test
    void submitsOnlyOneCapacityWindowBeforeWaitingAndDegradesUnstartedChunksOnExpiry() throws Exception {
        Queue<Runnable> submitted = new ConcurrentLinkedQueue<>();
        CountDownLatch initialWindowSubmitted = new CountDownLatch(2);
        Executor holdingExecutor = command -> {
            submitted.add(command);
            initialWindowSubmitted.countDown();
        };
        LlmChunkReviewScheduler scheduler = new LlmChunkReviewScheduler(holdingExecutor, 8, 2);
        AtomicInteger reviewCalls = new AtomicInteger();
        Queue<String> fallbackCategories = new ConcurrentLinkedQueue<>();

        CompletableFuture<List<Integer>> scheduled = CompletableFuture.supplyAsync(() -> scheduler.schedule(
            chunks(5),
            budget(Duration.ofMillis(150)),
            chunk -> {
                reviewCalls.incrementAndGet();
                return chunk.index();
            },
            (chunk, category, failure) -> {
                fallbackCategories.add(category);
                return -chunk.index();
            }
        ));

        assertThat(initialWindowSubmitted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(submitted).hasSize(2);
        assertThat(scheduled.get(2, TimeUnit.SECONDS)).containsExactly(-1, -2, -3, -4, -5);
        assertThat(reviewCalls.get()).isZero();
        assertThat(submitted).hasSize(2);
        assertThat(fallbackCategories)
            .hasSize(5)
            .allMatch(LlmChunkReviewScheduler.BUDGET_EXHAUSTED_CATEGORY::equals);
    }

    private List<PullRequestDiffChunk> chunks(int total) {
        return java.util.stream.IntStream.rangeClosed(1, total)
            .mapToObj(index -> new PullRequestDiffChunk(
                index,
                total,
                new PullRequestDiff("octocat", "Hello-World", 1, List.of()),
                0,
                0,
                0,
                List.of("test")
            ))
            .toList();
    }

    private ReviewBudget budget(Duration duration) {
        return ReviewBudget.startingAt(System.nanoTime(), duration);
    }
}
