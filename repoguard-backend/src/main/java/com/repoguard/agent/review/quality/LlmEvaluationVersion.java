package com.repoguard.agent.review.quality;

import org.springframework.util.StringUtils;

/** Immutable version tuple used to compare provider evaluation results. */
public record LlmEvaluationVersion(
    String provider,
    String model,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String chunkPolicyVersion
) {

    public LlmEvaluationVersion {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        promptVersion = requireText(promptVersion, "promptVersion");
        contextVersion = requireText(contextVersion, "contextVersion");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        chunkPolicyVersion = requireText(chunkPolicyVersion, "chunkPolicyVersion");
    }

    public String versionKey() {
        return provider + "/" + model
            + "|prompt=" + promptVersion
            + "|context=" + contextVersion
            + "|schema=" + schemaVersion
            + "|chunk=" + chunkPolicyVersion;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("LLM evaluation " + field + " must not be blank");
        }
        return value.trim();
    }
}
