package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LlmChunkReviewFallbackHandlerTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);

    @Test
    void constructorRejectsMissingFallbackDependencies() {
        assertThatThrownBy(() -> new LlmChunkReviewFallbackHandler(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsFailureAndReturnsMarkerForTheLaterFullDiffRulePass() {
        PullRequestDiffChunk chunk = chunk(2, 3);
        RuntimeException failure = new IllegalStateException("chunk failed");
        LlmChunkReviewFallbackHandler handler = new LlmChunkReviewFallbackHandler(metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(LlmChunkReviewAggregator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            LlmChunkReviewOutcome outcome = handler.fallback(
                chunk,
                LlmChunkReviewFallbackHandler.CHUNK_PARTIAL_FAILURE_CATEGORY,
                failure
            );

            assertThat(outcome.review().riskLevel()).isEqualTo("INFO");
            assertThat(outcome.review().findings()).isEmpty();
            assertThat(outcome.callResult()).isNull();
            verify(metrics).llmFallback(LlmChunkReviewFallbackHandler.CHUNK_PARTIAL_FAILURE_CATEGORY);
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(
                "chunkIndex=2",
                "chunkTotal=3",
                "reason=chunk_partial_failure",
                "exceptionType=java.lang.IllegalStateException"
            );
            assertThat(event.getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private PullRequestDiffChunk chunk(int index, int total) {
        PullRequestDiff diff = new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new PullRequestChangedFile(
                "src/B.java",
                "modified",
                1,
                0,
                "@@ -0,0 +1,1 @@\n+value"
            ))
        );
        return new PullRequestDiffChunk(index, total, diff, 1, 1, 0, List.of("test"));
    }
}
