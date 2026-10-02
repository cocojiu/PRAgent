package com.repoguard.agent.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;

/** Immutable accounting evidence for an estimate, never a provider invoice. */
public record LlmUsageCostSnapshot(
    Integer cachedInputTokens,
    Integer normalInputTokens,
    String usageSource,
    String costSource,
    String pricingVersion,
    String currency,
    String priceSource,
    BigDecimal inputTokenPricePerMillion,
    BigDecimal outputTokenPricePerMillion,
    BigDecimal cachedInputTokenPricePerMillion,
    BigDecimal estimatedAmount
) {
    public String toJson(ObjectMapper mapper) {
        try { return mapper.writeValueAsString(this); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cost snapshot encoding failed", ex); }
    }

    public static LlmUsageCostSnapshot fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, LlmUsageCostSnapshot.class); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cost snapshot decoding failed", ex); }
    }
}
