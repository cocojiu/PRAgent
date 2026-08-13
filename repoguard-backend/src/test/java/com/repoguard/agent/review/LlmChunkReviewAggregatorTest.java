package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LlmChunkReviewAggregatorTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ThreadPoolExecutor chunkExecutor = new BoundedExecutorFactory(
        new SimpleMeterRegistry(),
        new AsyncExecutorProperties()
    ).create("llm-chunk-test", 4, 16);
    private final LlmChunkReviewAggregator aggregator = new LlmChunkReviewAggregator(
        new LlmReviewPromptBuilder(),
        new LlmRuleReviewMerger(new RiskLevelRanker()),
        new LlmReviewQualityScorer(),
        new LlmReviewCostEstimator(),
        metrics,
        chunkExecutor,
        64,
        3
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewResultParser parser = new LlmReviewResultParser(
        objectMapper,
        new LlmReviewJsonExtractor(),
        new LlmReviewSchemaRepairer(objectMapper),
        new LlmReviewFindingMapper(),
        new LlmReviewParseFailureSummarizer()
    );

    @AfterEach
    void shutDownExecutor() {
        chunkExecutor.shutdownNow();
    }

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new LlmChunkReviewAggregator(
            new LlmReviewPromptBuilder(),
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            null,
            chunkExecutor,
            64,
            3
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingChunkExecutor() {
        assertThatThrownBy(() -> new LlmChunkReviewAggregator(
            new LlmReviewPromptBuilder(),
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            metrics,
            null,
            64,
            3
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("chunkExecutor");
    }

    @Test
    void aggregatesSuccessfulChunksAndDefersFailedChunksToTheFullDiffRulePass() {
        PullRequestDiff fullDiff = diff("src/A.java", "src/B.java", "src/C.java");
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, "src/A.java"),
            chunk(2, "src/B.java"),
            chunk(3, "src/C.java")
        );
        ReviewPolicySettings settings = settings();
        ReviewTask task = new ReviewTask();
        LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
            String path = chunkDiff.files().getFirst().filename();
            if (path.endsWith("B.java")) {
                throw new IllegalStateException("chunk failed");
            }
            return new LlmCallResult(llmJson(path), 100, 25, 125);
        };
        ReviewPipelineContext context = new ReviewPipelineContext(
            task,
            fullDiff,
            settings,
            "promptSummary",
            System.nanoTime(),
            caller
        );
        ReviewResult result = aggregator.aggregate(context, fullDiff, chunks, parser, budget());

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(result.findings()).extracting(ReviewFindingResult::source).containsExactly("LLM", "LLM");
        assertThat(result.llmPromptTokens()).isEqualTo(200);
        assertThat(result.llmCompletionTokens()).isEqualTo(50);
        assertThat(result.llmTotalTokens()).isEqualTo(250);
        assertThat(result.llmEstimatedCost()).isEqualByComparingTo("0.000400");
        assertThat(result.llmPromptSummary()).contains("chunked=true", "failedChunks=1");
        verify(metrics).llmFallback(LlmChunkReviewAggregator.CHUNK_PARTIAL_FAILURE_CATEGORY);
    }

    @Test
    void reviewsChunksInParallelBoundedByExecutorThreadsAndKeepsChunkOrder() {
        ThreadPoolExecutor boundedExecutor = new BoundedExecutorFactory(
            new SimpleMeterRegistry(),
            new AsyncExecutorProperties()
        ).create("llm-chunk-parallel-test", 2, 16);
        try {
            LlmChunkReviewAggregator boundedAggregator = new LlmChunkReviewAggregator(
                new LlmReviewPromptBuilder(),
                new LlmRuleReviewMerger(new RiskLevelRanker()),
                new LlmReviewQualityScorer(),
                new LlmReviewCostEstimator(),
                metrics,
                boundedExecutor,
                64,
                2
            );
            PullRequestDiff fullDiff = diff("src/A.java", "src/B.java", "src/C.java", "src/D.java");
            List<PullRequestDiffChunk> chunks = List.of(
                chunk(1, 4, "src/A.java"),
                chunk(2, 4, "src/B.java"),
                chunk(3, 4, "src/C.java"),
                chunk(4, 4, "src/D.java")
            );
            CountDownLatch pairStarted = new CountDownLatch(2);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                pairStarted.countDown();
                try {
                    if (!pairStarted.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("chunks were not reviewed in parallel");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } finally {
                    active.decrementAndGet();
                }
                return new LlmCallResult(llmJson(chunkDiff.files().getFirst().filename()), 100, 25, 125);
            };
            ReviewPipelineContext context = new ReviewPipelineContext(
                new ReviewTask(),
                fullDiff,
                settings(),
                "promptSummary",
                System.nanoTime(),
                caller
            );

            ReviewResult result = boundedAggregator.aggregate(context, fullDiff, chunks, parser, budget());

            assertThat(maxActive.get()).isEqualTo(2);
            assertThat(result.llmParseStatus()).isNull();
            assertThat(result.findings())
                .extracting(ReviewFindingResult::filePath)
                .containsExactly("src/A.java", "src/B.java", "src/C.java", "src/D.java");
        } finally {
            boundedExecutor.shutdownNow();
        }
    }

    @Test
    void submitsOnlyOneSlidingWindowBeforeWaitingForCompletion() throws Exception {
        Queue<Runnable> submitted = new ConcurrentLinkedQueue<>();
        CountDownLatch initialWindowSubmitted = new CountDownLatch(2);
        Executor holdingExecutor = command -> {
            submitted.add(command);
            initialWindowSubmitted.countDown();
        };
        LlmChunkReviewAggregator windowedAggregator = new LlmChunkReviewAggregator(
            new LlmReviewPromptBuilder(),
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            metrics,
            holdingExecutor,
            64,
            2
        );
        PullRequestDiff fullDiff = diff(
            "src/A.java",
            "src/B.java",
            "src/C.java",
            "src/D.java",
            "src/E.java"
        );
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, 5, "src/A.java"),
            chunk(2, 5, "src/B.java"),
            chunk(3, 5, "src/C.java"),
            chunk(4, 5, "src/D.java"),
            chunk(5, 5, "src/E.java")
        );
        AtomicInteger llmCalls = new AtomicInteger();
        ReviewPipelineContext context = new ReviewPipelineContext(
            new ReviewTask(),
            fullDiff,
            settings(),
            "promptSummary",
            System.nanoTime(),
            (ignoredSettings, ignoredTask, ignoredDiff) -> {
                llmCalls.incrementAndGet();
                return new LlmCallResult(llmJson("unused"), 100, 25, 125);
            }
        );
        CompletableFuture<ReviewResult> result = CompletableFuture.supplyAsync(
            () -> windowedAggregator.aggregate(
                context,
                fullDiff,
                chunks,
                parser,
                ReviewBudget.startingAt(System.nanoTime(), Duration.ofMillis(150))
            )
        );

        assertThat(initialWindowSubmitted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(submitted).hasSize(2);
        assertThat(result.get(2, TimeUnit.SECONDS).llmParseStatus())
            .isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(llmCalls.get()).isZero();
        assertThat(submitted).hasSize(2);
    }

    @Test
    void capsLlmCallsAndFallsBackChunksBeyondConfiguredTotal() {
        LlmChunkReviewAggregator cappedAggregator = new LlmChunkReviewAggregator(
            new LlmReviewPromptBuilder(),
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            metrics,
            Runnable::run,
            2,
            1
        );
        PullRequestDiff fullDiff = diff("src/A.java", "src/B.java", "src/C.java", "src/D.java");
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, 4, "src/A.java"),
            chunk(2, 4, "src/B.java"),
            chunk(3, 4, "src/C.java"),
            chunk(4, 4, "src/D.java")
        );
        AtomicInteger llmCalls = new AtomicInteger();
        ReviewPipelineContext context = new ReviewPipelineContext(
            new ReviewTask(),
            fullDiff,
            settings(),
            "promptSummary",
            System.nanoTime(),
            (ignoredSettings, ignoredTask, chunkDiff) -> {
                llmCalls.incrementAndGet();
                String path = chunkDiff.files().getFirst().filename();
                return new LlmCallResult(llmJson(path), 100, 25, 125);
            }
        );
        ReviewResult result = cappedAggregator.aggregate(context, fullDiff, chunks, parser, budget());

        assertThat(llmCalls.get()).isEqualTo(2);
        assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(result.findings())
            .extracting(ReviewFindingResult::filePath)
            .containsExactly("src/A.java", "src/B.java");
        verify(metrics, times(2)).llmFallback(LlmChunkReviewAggregator.CHUNK_LIMIT_EXCEEDED_CATEGORY);
    }

    @Test
    void executorRejectionFallsBackRejectedChunksWithoutDiscardingCompletedWork() {
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectingExecutor = command -> {
            if (submissions.incrementAndGet() == 1) {
                command.run();
                return;
            }
            throw new RejectedExecutionException("saturated");
        };
        LlmChunkReviewAggregator rejectingAggregator = new LlmChunkReviewAggregator(
            new LlmReviewPromptBuilder(),
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            metrics,
            rejectingExecutor,
            64,
            2
        );
        PullRequestDiff fullDiff = diff("src/A.java", "src/B.java", "src/C.java");
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, 3, "src/A.java"),
            chunk(2, 3, "src/B.java"),
            chunk(3, 3, "src/C.java")
        );
        ReviewPipelineContext context = new ReviewPipelineContext(
            new ReviewTask(),
            fullDiff,
            settings(),
            "promptSummary",
            System.nanoTime(),
            (ignoredSettings, ignoredTask, chunkDiff) -> new LlmCallResult(
                llmJson(chunkDiff.files().getFirst().filename()),
                100,
                25,
                125
            )
        );
        ReviewResult result = rejectingAggregator.aggregate(context, fullDiff, chunks, parser, budget());

        assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(result.findings())
            .extracting(ReviewFindingResult::filePath)
            .containsExactly("src/A.java");
        assertThat(result.llmPromptTokens()).isEqualTo(100);
        verify(metrics, times(2)).llmFallback(LlmChunkReviewAggregator.EXECUTOR_REJECTED_CATEGORY);
    }

    @Test
    void budgetExpiryKeepsCompletedChunksAndInterruptsUnfinishedCall() throws Exception {
        ThreadPoolExecutor boundedExecutor = new BoundedExecutorFactory(
            new SimpleMeterRegistry(),
            new AsyncExecutorProperties()
        ).create("llm-chunk-budget-test", 2, 4);
        try {
            LlmChunkReviewAggregator budgetedAggregator = new LlmChunkReviewAggregator(
                new LlmReviewPromptBuilder(),
                new LlmRuleReviewMerger(new RiskLevelRanker()),
                new LlmReviewQualityScorer(),
                new LlmReviewCostEstimator(),
                metrics,
                boundedExecutor,
                64,
                2
            );
            PullRequestDiff fullDiff = diff("src/A.java", "src/B.java");
            List<PullRequestDiffChunk> chunks = List.of(
                chunk(1, 2, "src/A.java"),
                chunk(2, 2, "src/B.java")
            );
            CountDownLatch neverRelease = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
                String path = chunkDiff.files().getFirst().filename();
                if (path.endsWith("A.java")) {
                    try {
                        neverRelease.await();
                    } catch (InterruptedException ex) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                }
                return new LlmCallResult(llmJson(path), 100, 25, 125);
            };
            ReviewPipelineContext context = new ReviewPipelineContext(
                new ReviewTask(),
                fullDiff,
                settings(),
                "promptSummary",
                System.nanoTime(),
                caller
            );
            ReviewResult result = budgetedAggregator.aggregate(
                context,
                fullDiff,
                chunks,
                parser,
                ReviewBudget.startingAt(System.nanoTime(), Duration.ofMillis(150))
            );

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
            assertThat(result.findings())
                .extracting(ReviewFindingResult::source)
                .containsExactly("LLM");
            assertThat(result.findings())
                .extracting(ReviewFindingResult::filePath)
                .containsExactly("src/B.java");
            assertThat(result.llmPromptTokens()).isEqualTo(100);
        } finally {
            boundedExecutor.shutdownNow();
        }
    }

    @Test
    void propagatesLogContextIntoChunkReviewTasks() {
        PullRequestDiff fullDiff = diff("src/A.java", "src/B.java");
        List<PullRequestDiffChunk> chunks = List.of(chunk(1, 2, "src/A.java"), chunk(2, 2, "src/B.java"));
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setPrNumber(512);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        Queue<Map<String, String>> capturedContexts = new ConcurrentLinkedQueue<>();
        LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
            capturedContexts.add(Map.of(
                LogContext.TASK_ID, String.valueOf(MDC.get(LogContext.TASK_ID)),
                LogContext.REPOSITORY, String.valueOf(MDC.get(LogContext.REPOSITORY)),
                LogContext.TRACE_ID, String.valueOf(MDC.get(LogContext.TRACE_ID))
            ));
            return new LlmCallResult(llmJson(chunkDiff.files().getFirst().filename()), 100, 25, 125);
        };
        ReviewPipelineContext context = new ReviewPipelineContext(
            task,
            fullDiff,
            settings(),
            "promptSummary",
            System.nanoTime(),
            caller
        );
        MDC.put(LogContext.TRACE_ID, "trace-chunk");
        try {
            aggregator.aggregate(context, fullDiff, chunks, parser, budget());
        } finally {
            MDC.remove(LogContext.TRACE_ID);
        }

        assertThat(capturedContexts).hasSize(2).allSatisfy(values -> {
            assertThat(values.get(LogContext.TASK_ID)).isEqualTo("42");
            assertThat(values.get(LogContext.REPOSITORY)).isEqualTo("octocat/Hello-World");
            assertThat(values.get(LogContext.TRACE_ID)).isEqualTo("trace-chunk");
        });
    }

    @Test
    void logsChunkFailureWithChunkIndexAndStackTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmChunkReviewAggregator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            PullRequestDiff fullDiff = diff("src/A.java", "src/B.java");
            List<PullRequestDiffChunk> chunks = List.of(chunk(1, 2, "src/A.java"), chunk(2, 2, "src/B.java"));
            LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
                String path = chunkDiff.files().getFirst().filename();
                if (path.endsWith("B.java")) {
                    throw new IllegalStateException("chunk failed");
                }
                return new LlmCallResult(llmJson(path), 100, 25, 125);
            };
            ReviewPipelineContext context = new ReviewPipelineContext(
                new ReviewTask(),
                fullDiff,
                settings(),
                "promptSummary",
                System.nanoTime(),
                caller
            );
            aggregator.aggregate(context, fullDiff, chunks, parser, budget());

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(
                "chunkIndex=2",
                "chunkTotal=2",
                "exceptionType=java.lang.IllegalStateException"
            );
            assertThat(event.getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private PullRequestDiff diff(String... paths) {
        return new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            java.util.Arrays.stream(paths).map(this::file).toList()
        );
    }

    private PullRequestDiffChunk chunk(int index, String path) {
        return chunk(index, 3, path);
    }

    private PullRequestDiffChunk chunk(int index, int total, String path) {
        PullRequestDiff diff = diff(path);
        return new PullRequestDiffChunk(index, total, diff, 1, 1, 0, List.of("test"));
    }

    private PullRequestChangedFile file(String path) {
        return new PullRequestChangedFile(path, "modified", 1, 0, "@@ -0,0 +1,1 @@\n+value");
    }

    private String llmJson(String path) {
        return """
            {
              "riskLevel": "HIGH",
              "findings": [
                {
                  "severity": "HIGH",
                  "filePath": "%s",
                  "lineNumber": 1,
                  "message": "Potential issue from chunk review",
                  "recommendation": "Apply a targeted fix"
                }
              ]
            }
            """.formatted(path);
    }

    private ReviewPolicySettings settings() {
        return new ReviewPolicySettings(
            true,
            true,
            "openai",
            "gpt-test",
            "https://llm.example.test",
            "llm-key",
            30,
            BigDecimal.valueOf(0.2),
            1024,
            true,
            1,
            6,
            700,
            4,
            450,
            BigDecimal.ONE,
            BigDecimal.valueOf(4)
        );
    }

    private ReviewBudget budget() {
        return ReviewBudget.startingAt(System.nanoTime(), Duration.ofSeconds(5));
    }
}
