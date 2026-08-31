package com.repoguard.agent.observability;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal W3C Trace Context value used until a full tracing SDK is enabled.
 * Only trace identifiers and flags are carried; request or code payloads are
 * deliberately not part of this value.
 */
public record W3CTraceContext(String traceId, String spanId, int traceFlags) {

    public static final String VERSION = "00";
    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    public W3CTraceContext {
        traceId = normalize(traceId, TRACE_ID_HEX_LENGTH, "traceId");
        spanId = normalize(spanId, SPAN_ID_HEX_LENGTH, "spanId");
        if (allZero(traceId) || allZero(spanId)) {
            throw new IllegalArgumentException("W3C trace identifiers must not be all zero");
        }
        if (traceFlags < 0 || traceFlags > 0xff) {
            throw new IllegalArgumentException("traceFlags must fit in one byte");
        }
    }

    public static W3CTraceContext root() {
        return new W3CTraceContext(randomHex(16), randomHex(8), 0);
    }

    public static Optional<W3CTraceContext> parse(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        if (!isHex(parts[1], TRACE_ID_HEX_LENGTH)
            || !isHex(parts[2], SPAN_ID_HEX_LENGTH)
            || !isHex(parts[3], 2)
            || allZero(parts[1])
            || allZero(parts[2])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new W3CTraceContext(
                parts[1],
                parts[2],
                Integer.parseInt(parts[3], 16)
            ));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public W3CTraceContext child() {
        return new W3CTraceContext(traceId, randomHex(8), traceFlags);
    }

    public String traceparent() {
        return VERSION + "-" + traceId + "-" + spanId + "-" + HEX.toHexDigits(traceFlags).substring(6);
    }

    private static String normalize(String value, int length, String name) {
        if (!isHex(value, length)) {
            throw new IllegalArgumentException(name + " must be exactly " + length + " lowercase hexadecimal characters");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean isHex(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.digit(character, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        do {
            RANDOM.nextBytes(bytes);
        } while (allZero(HEX.formatHex(bytes)));
        return HEX.formatHex(bytes);
    }
}
