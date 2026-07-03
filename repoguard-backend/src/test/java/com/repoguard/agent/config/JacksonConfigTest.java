package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonConfigTest {

    @Test
    void objectMapperSupportsJavaTimeAndBootLikeDefaults() throws Exception {
        var mapper = new JacksonConfig().objectMapper();

        String json = mapper.writeValueAsString(Map.of(
            "createdAt",
            LocalDateTime.of(2026, 7, 3, 21, 35, 0)
        ));

        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(json).contains("\"createdAt\":\"2026-07-03T21:35:00\"");
    }
}
