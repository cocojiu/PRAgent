package com.repoguard.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    @Test
    void unhandledExceptionReturnsSafeMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleException(
            new IllegalStateException("jdbc:mysql://internal-host:3306/repoguard password=secret")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("系统内部异常，请联系管理员。");
        assertThat(response.getBody().message()).doesNotContain("jdbc:mysql", "password=secret");
    }

    @Test
    void sanitizeLogMessageMasksStructuredSecrets() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        String sanitized = handler.sanitizeLogMessage("""
            request failed payload={"refreshToken":"raw-refresh","clientSecret": "raw-secret","apiKey": "raw-key"}
            password=raw-password Authorization=Bearer abc.def.ghi
            """);

        assertThat(sanitized)
            .contains("\"refreshToken\":\"****\"")
            .contains("\"clientSecret\": \"****\"")
            .contains("\"apiKey\": \"****\"")
            .contains("password=****")
            .contains("Bearer ****")
            .doesNotContain("raw-refresh", "raw-secret", "raw-key", "raw-password", "abc.def.ghi");
    }
}
