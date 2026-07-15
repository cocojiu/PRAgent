package com.repoguard.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

class SanitizingLogConverterTest {

    @Test
    void sanitizesFormattedMessagesAndCompleteThrowableChains() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("safe-log-test");
        IllegalArgumentException cause = new IllegalArgumentException(
            "Authorization=Bearer raw-cause-token"
        );
        IllegalStateException failure = new IllegalStateException(
            "jdbc:mysql://internal-db:3306/repoguard password=raw-db-password",
            cause
        );
        LoggingEvent event = new LoggingEvent(
            getClass().getName(),
            logger,
            Level.ERROR,
            "request failed password={} endpoint={}",
            failure,
            new Object[] {"raw-request-password", "jdbc:mysql://internal-db:3306/repoguard"}
        );

        SanitizingMessageConverter messageConverter = new SanitizingMessageConverter();
        SanitizingThrowableConverter throwableConverter = new SanitizingThrowableConverter();
        throwableConverter.setContext(context);
        throwableConverter.start();
        try {
            assertThat(messageConverter.convert(event))
                .isEqualTo("request failed password=**** endpoint=jdbc:****");

            assertThat(throwableConverter.convert(event))
                .contains(
                    "java.lang.IllegalStateException: jdbc:**** password=****",
                    "Caused by: java.lang.IllegalArgumentException: Authorization=Bearer ****",
                    "SanitizingLogConverterTest"
                )
                .doesNotContain(
                    "internal-db",
                    "raw-db-password",
                    "raw-cause-token",
                    "raw-request-password"
                );
        } finally {
            throwableConverter.stop();
            context.stop();
        }
    }
}
