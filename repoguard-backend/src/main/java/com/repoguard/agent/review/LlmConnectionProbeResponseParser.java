package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Extracts and validates the review payload returned by an LLM connection probe.
 */
@Component
public class LlmConnectionProbeResponseParser {

    private final ObjectMapper objectMapper;
    private final LlmReviewResultParser llmReviewResultParser;

    public LlmConnectionProbeResponseParser(
        ObjectMapper objectMapper,
        LlmReviewResultParser llmReviewResultParser
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.llmReviewResultParser = Objects.requireNonNull(llmReviewResultParser, "llmReviewResultParser");
    }

    public String extractReviewContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response == null ? "" : response);
        for (String pointer : List.of(
            "/choices/0/message/content",
            "/choices/0/text",
            "/output_text",
            "/output/0/content/0/text",
            "/content"
        )) {
            String content = nodeText(root.at(pointer));
            if (StringUtils.hasText(content)) {
                return content.trim();
            }
        }
        return "";
    }

    public void validateReviewJson(String content) {
        llmReviewResultParser.parse(content);
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String text = nodeText(item);
                if (StringUtils.hasText(text)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text.trim());
                }
            }
            return builder.toString();
        }
        if (node.isObject()) {
            for (String field : List.of("text", "content")) {
                String text = nodeText(node.path(field));
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }
}
