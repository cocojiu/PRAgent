package com.repoguard.agent.review;

class LlmReviewJsonExtractor {

    String extractJsonObject(String content) {
        String trimmed = stripJsonFence(content);
        int start = trimmed.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("LLM result does not contain a JSON object");
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("LLM result contains an incomplete JSON object");
    }

    private String stripJsonFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```\\s*(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return trimmed;
    }
}
