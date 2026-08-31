package com.repoguard.agent.testlogging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** Keeps expected fallback warnings out of the console while direct log assertions still see them. */
public final class SuppressExpectedLlmFallbackFilter extends Filter<ILoggingEvent> {

    private static final String PIPELINE_LOGGER = "com.repoguard.agent.review.LlmReviewPipeline";
    private static final String CHUNK_LOGGER = "com.repoguard.agent.review.LlmChunkReviewAggregator";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event == null || !Level.WARN.equals(event.getLevel())) {
            return FilterReply.NEUTRAL;
        }
        String loggerName = event.getLoggerName();
        String message = event.getFormattedMessage();
        if ((PIPELINE_LOGGER.equals(loggerName) || CHUNK_LOGGER.equals(loggerName))
            && message != null
            && message.contains("result=fallback")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
