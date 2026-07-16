package com.repoguard.agent.common;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

public final class SanitizingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy throwableProxy) {
        return SensitiveTextSanitizer.sanitizePreservingWhitespace(
            super.throwableProxyToString(throwableProxy)
        );
    }
}
