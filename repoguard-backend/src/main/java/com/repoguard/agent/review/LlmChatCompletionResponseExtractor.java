package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LlmChatCompletionResponseExtractor {

    private final ObjectMapper objectMapper;

    public LlmChatCompletionResponseExtractor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public LlmChatCompletionResponse extract(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IllegalStateException("Empty LLM HTTP response");
        }
        if ("length".equalsIgnoreCase(root.at("/choices/0/finish_reason").asText())) {
            throw new ExternalCallException(
                "LLM", "llm_response_truncated", false, null, "finishReason=length", null
            );
        }
        return new LlmChatCompletionResponse(
            extractContent(root),
            intValue(root.at("/usage/prompt_tokens")),
            intValue(root.at("/usage/completion_tokens")),
            intValue(root.at("/usage/total_tokens"))
        );
    }

    public LlmChatCompletionResponse extract(String response) throws java.io.IOException {
        return extract(objectMapper.readTree(response == null ? "" : response));
    }

    private String extractContent(JsonNode root) {
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
            // Some OpenAI-compatible providers return structured content as an
            // object instead of a JSON-encoded string.
            return node.toString();
        }
        return "";
    }

    private Integer intValue(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
    }
}
