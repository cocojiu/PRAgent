package com.repoguard.agent.review;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Extracts and validates the review payload returned by an LLM connection probe.
 */
@Component
public class LlmConnectionProbeResponseParser {

    private final LlmChatCompletionResponseExtractor responseExtractor;
    private final LlmReviewResultParser llmReviewResultParser;

    public LlmConnectionProbeResponseParser(
        LlmChatCompletionResponseExtractor responseExtractor,
        LlmReviewResultParser llmReviewResultParser
    ) {
        this.responseExtractor = Objects.requireNonNull(responseExtractor, "responseExtractor");
        this.llmReviewResultParser = Objects.requireNonNull(llmReviewResultParser, "llmReviewResultParser");
    }

    public String extractReviewContent(String response) throws Exception {
        return responseExtractor.extract(response).content();
    }

    public void validateReviewJson(String content) {
        llmReviewResultParser.parse(content);
    }

}
