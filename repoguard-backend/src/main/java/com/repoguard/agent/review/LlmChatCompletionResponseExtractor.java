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
        LlmCallResult usage = extractUsage(root);
        return new LlmChatCompletionResponse(
            extractContent(root),
            usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
            usage.cachedInputTokens(), usage.usageSource()
        );
    }

    LlmCallResult extractUsage(JsonNode root) {
        JsonNode usage = root == null ? null : root.path("usage");
        Integer prompt = usage == null ? null : intValue(usage.path("prompt_tokens"));
        JsonNode cache = usage == null ? null : usage.at("/prompt_tokens_details/cached_tokens");
        Integer cached = intValue(cache);
        boolean invalid = cache != null && !cache.isMissingNode() && !cache.isNull()
            && (cached == null || prompt == null || cached > prompt);
        if (invalid) cached = null;
        String source = invalid ? "INVALID_CACHE_DETAILS"
            : cached != null ? "OPENAI_COMPATIBLE_CACHE_DETAILS"
            : prompt != null ? "CACHE_USAGE_UNKNOWN" : "UNKNOWN";
        return new LlmCallResult(
            "",
            prompt,
            usage == null ? null : intValue(usage.path("completion_tokens")),
            usage == null ? null : intValue(usage.path("total_tokens")),
            LlmStructuredOutputStatus.NOT_REQUESTED, cached, source
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
        return node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0
            ? null : node.asInt();
    }
}
