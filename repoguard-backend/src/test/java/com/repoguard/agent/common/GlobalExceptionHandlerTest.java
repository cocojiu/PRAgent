package com.repoguard.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    @Test
    void conflictBusinessExceptionReturnsHttp409() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
            new BusinessException(ErrorCode.CONFLICT, "状态已变化，请刷新")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("状态已变化，请刷新");
    }

    @Test
    void unhandledExceptionReturnsSafeMessageAndCorrelatesTheDiagnosticLog() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new EagerListAppender();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        logger.addAppender(appender);
        MDC.put("traceId", "trace-handler-test");

        try {
            ResponseEntity<ApiResponse<Void>> response = handler.handleException(
                new IllegalStateException(
                    "jdbc:mysql://internal-host:3306/repoguard password=raw-password",
                    new IllegalArgumentException("Authorization=Bearer raw-cause-token")
                )
            );

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().message()).isEqualTo("系统内部异常，请联系管理员。");
            assertThat(response.getBody().message()).doesNotContain("jdbc:mysql", "raw-password");

            String errorId = response.getHeaders().getFirst(GlobalExceptionHandler.ERROR_ID_HEADER);
            assertThat(errorId).matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                .contains("traceId=trace-handler-test", "errorId=" + errorId, "message=jdbc:**** password=****")
                .doesNotContain("internal-host", "raw-password", "raw-cause-token");
            assertThat(event.getMDCPropertyMap()).containsEntry("errorId", errorId);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getCause()).isNotNull();
            assertThat(MDC.get("errorId")).isNull();
        } finally {
            MDC.remove("traceId");
            logger.detachAppender(appender);
            logger.setAdditive(previousAdditive);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void sanitizeLogMessageMasksStructuredSecrets() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        String sanitized = handler.sanitizeLogMessage("""
            request failed payload={"refreshToken":"raw-refresh","clientSecret": "raw-secret","apiKey": "raw-key"}
            password=raw-password Authorization=Bearer abc.def.ghi
            callback=https://user:raw-pass@example.com/hook?access_token=raw-access&sign=raw-sign
            """);

        assertThat(sanitized)
            .contains("\"refreshToken\":\"****\"")
            .contains("\"clientSecret\": \"****\"")
            .contains("\"apiKey\": \"****\"")
            .contains("password=****")
            .contains("Bearer ****")
            .contains("https://user:****@example.com/hook")
            .contains("access_token=****", "sign=****")
            .doesNotContain(
                "raw-refresh",
                "raw-secret",
                "raw-key",
                "raw-password",
                "abc.def.ghi",
                "raw-pass",
                "raw-access",
                "raw-sign"
            );
    }

    private static final class EagerListAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
