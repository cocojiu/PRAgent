package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewQualityBaselineSnapshotCodec {

    private final ObjectMapper objectMapper;

    public ReviewQualityBaselineSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encode(ReviewQualityBaseline baseline) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(baseline, "baseline"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Quality baseline snapshot serialization failed", ex);
        }
    }

    public ReviewQualityBaseline decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Quality baseline snapshot payload is empty");
        }
        try {
            return objectMapper.readValue(payload, ReviewQualityBaseline.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Quality baseline snapshot deserialization failed", ex);
        }
    }
}
