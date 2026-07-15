package com.repoguard.agent.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SanitizingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return message == null ? "" : SensitiveTextSanitizer.sanitizePreservingWhitespace(message);
    }
}
