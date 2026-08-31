package com.repoguard.agent.review;

/**
 * Describes how an LLM provider constrains the response returned to the review pipeline.
 */
public enum LlmStructuredOutputMode {
    JSON_SCHEMA("json_schema"),
    JSON_OBJECT("json_object"),
    NONE("none");

    private final String code;

    LlmStructuredOutputMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean enabled() {
        return this != NONE;
    }
}
