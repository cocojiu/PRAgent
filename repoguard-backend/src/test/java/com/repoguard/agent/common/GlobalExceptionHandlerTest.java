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
}
