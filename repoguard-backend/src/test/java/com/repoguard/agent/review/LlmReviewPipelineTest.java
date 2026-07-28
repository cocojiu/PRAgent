package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LlmReviewPipelineTest {

    private final RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
    private final LlmReviewPromptBuilder promptBuilder = new LlmReviewPromptBuilder();
    private final LlmRuleReviewMerger reviewMerger = new LlmRuleReviewMerger(new RiskLevelRanker());
    private final LlmReviewQualityScorer qualityScorer = new LlmReviewQualityScorer();
    private final LlmReviewCostEstimator costEstimator = new LlmReviewCostEstimator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewResultParser reviewResultParser = parser();
    private final LlmFallbackReasonClassifier fallbackReasonClassifier = new LlmFallbackReasonClassifier();
    private final PullRequestDiffChunker diffChunker = DiffChunkingTestFixtures.chunker();
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final Executor llmChunkExecutor = Runnable::run;

    @Test
    void constructorRejectsMissingExplicitDependencies() {
        assertMissing("ruleBasedReviewer", () -> pipeline(null, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker));
        assertMissing("promptBuilder", () -> pipeline(ruleBasedReviewer, null, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker));
        assertMissing("reviewMerger", () -> pipeline(ruleBasedReviewer, promptBuilder, null, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker));
        assertMissing("qualityScorer", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, null, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker));
        assertMissing("costEstimator", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, null, reviewResultParser, fallbackReasonClassifier, diffChunker));
        assertMissing("reviewResultParser", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, null, fallbackReasonClassifier, diffChunker));
        assertMissing("metrics", () -> new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            reviewResultParser,
            null,
            fallbackReasonClassifier,
            diffChunker,
            llmChunkExecutor,
            properties()
        ));
        assertMissing("fallbackReasonClassifier", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, null, diffChunker));
        assertMissing("diffChunker", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, null));
        assertMissing("llmChunkExecutor", () -> new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            reviewResultParser,
            metrics,
            fallbackReasonClassifier,
            diffChunker,
            null,
            properties()
        ));
        assertMissing("budgetProperties", () -> new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            reviewResultParser,
            metrics,
            fallbackReasonClassifier,
            diffChunker,
            llmChunkExecutor,
            null
        ));
    }

    @Test
    void executeClassifiesInternalFailureAndLogsErrorWithStackTrace() {
        GithubPullRequestDiff diff = diff();
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));
        LlmReviewCaller caller = (settings, task, callDiff) -> {
            throw new NullPointerException();
        };
        Logger logger = (Logger) LoggerFactory.getLogger(LlmReviewPipeline.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            ReviewResult result = pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker)
                .execute(context(diff, caller));

            assertThat(result.llmStatus()).isEqualTo("FALLBACK");
            assertThat(result.statusDetail()).isEqualTo("internal error: NullPointerException");
            verify(metrics).llmFallback(LlmFallbackReasonClassifier.INTERNAL_ERROR_CATEGORY);
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("exceptionType=java.lang.NullPointerException");
            assertThat(event.getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void executeClassifiesExternalFailureAndLogsWarn() {
        GithubPullRequestDiff diff = diff();
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));
        LlmReviewCaller caller = (settings, task, callDiff) -> {
            throw new ExternalCallException("LLM", "llm_rate_limited", true, 429, "operation=chat_completions", null);
        };
        Logger logger = (Logger) LoggerFactory.getLogger(LlmReviewPipeline.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            ReviewResult result = pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker)
                .execute(context(diff, caller));

            assertThat(result.llmStatus()).isEqualTo("FALLBACK");
            assertThat(result.statusDetail()).contains("category=llm_rate_limited", "status=429");
            verify(metrics).llmFallback("llm_rate_limited");
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(
                "exceptionType=com.repoguard.agent.external.ExternalCallException",
                "category=llm_rate_limited"
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void executeRethrowsInternalFailureWhenFallbackToRulesDisabled() {
        GithubPullRequestDiff diff = diff();
        LlmReviewCaller caller = (settings, task, callDiff) -> {
            throw new NullPointerException();
        };
        LlmReviewPipeline pipeline = pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, reviewResultParser, fallbackReasonClassifier, diffChunker);

        assertThatThrownBy(() -> pipeline.execute(context(diff, settings(false), caller)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void singleChunkUsesPipelineDeadlineAndInterruptsUnfinishedCall() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ReviewPipelineBudgetProperties properties = properties();
            properties.setBudgetMs(100);
            LlmReviewPipeline budgetedPipeline = new LlmReviewPipeline(
                ruleBasedReviewer,
                promptBuilder,
                reviewMerger,
                qualityScorer,
                costEstimator,
                reviewResultParser,
                metrics,
                fallbackReasonClassifier,
                diffChunker,
                executor,
                properties
            );
            GithubPullRequestDiff diff = diff();
            CountDownLatch neverRelease = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));
            LlmReviewCaller caller = (settings, task, callDiff) -> {
                try {
                    neverRelease.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new LlmCallResult("{}", 0, 0, 0);
            };

            ReviewResult result = budgetedPipeline.execute(context(diff, caller));

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.llmStatus()).isEqualTo("FALLBACK");
            assertThat(result.statusDetail()).contains("category=budget_exhausted");
            verify(metrics).llmFallback(LlmChunkReviewAggregator.BUDGET_EXHAUSTED_CATEGORY);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertMissing(String dependencyName, ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining(dependencyName);
    }

    private LlmReviewPipeline pipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        LlmReviewResultParser reviewResultParser,
        LlmFallbackReasonClassifier fallbackReasonClassifier,
        PullRequestDiffChunker diffChunker
    ) {
        return new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            reviewResultParser,
            metrics,
            fallbackReasonClassifier,
            diffChunker,
            llmChunkExecutor,
            properties()
        );
    }

    private ReviewPipelineBudgetProperties properties() {
        return new ReviewPipelineBudgetProperties();
    }

    private ReviewPipelineContext context(GithubPullRequestDiff diff, LlmReviewCaller caller) {
        return context(diff, settings(true), caller);
    }

    private ReviewPipelineContext context(GithubPullRequestDiff diff, ReviewPolicySettings settings, LlmReviewCaller caller) {
        return new ReviewPipelineContext(
            new ReviewTask(),
            diff,
            settings,
            "promptSummary",
            System.nanoTime(),
            caller
        );
    }

    private GithubPullRequestDiff diff() {
        return new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new GithubChangedFile("src/A.java", "modified", 1, 0, "@@ -0,0 +1,1 @@\n+value"))
        );
    }

    private ReviewPolicySettings settings(boolean fallbackToRules) {
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
            fallbackToRules,
            1,
            99,
            700,
            4,
            450,
            BigDecimal.ONE,
            BigDecimal.valueOf(4)
        );
    }

    private LlmReviewResultParser parser() {
        return new LlmReviewResultParser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        );
    }
}
