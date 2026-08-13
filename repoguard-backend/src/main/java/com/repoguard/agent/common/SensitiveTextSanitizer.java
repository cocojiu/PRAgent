package com.repoguard.agent.common;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SensitiveTextSanitizer {

    private static final String SENSITIVE_FIELD_NAME_PATTERN = "(?:"
        + "[\\w.-]*(?:token|password|secret)(?:[_-]?value)?"
        + "|[\\w.-]*(?:api[-_ ]?key|private[_-]?key|secret[_-]?key|signing[_-]?key|access[_-]?key)"
        + "(?:[_-]?value)?"
        + "|key|sign"
        + ")";

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
        "(?i)\\bjdbc:[a-z0-9]+:(?://)?[^\\s,;\"']+"
    );
    private static final Pattern URI_CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://[^\\s/:@]+:)[^\\s/@]+(@)"
    );
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile(
        "(?i)([?&](?:access_token|token|secret|password|key|sign)=)[^\\s&\"']+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)([\"']?\\b" + SENSITIVE_FIELD_NAME_PATTERN + "\\b[\"']?\\s*[:=]\\s*)"
            + "(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)"
    );
    private static final Pattern SENSITIVE_QUOTED_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)([\"']?\\b" + SENSITIVE_FIELD_NAME_PATTERN + "\\b[\"']?\\s*[:=]\\s*)"
            + "(\"[^\"]*\"|'[^']*')"
    );
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
        "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern OPENAI_STYLE_API_KEY_PATTERN = Pattern.compile(
        "\\bsk-[A-Za-z0-9_-]{8,}\\b"
    );
    private static final Pattern KNOWN_CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)\\b(?:"
            + "gh[pousr]_[a-z0-9_]{20,}"
            + "|whsec_[a-z0-9_-]{12,}"
            + "|AKIA[0-9A-Z]{16}"
            + "|xox[baprs]-[a-z0-9-]{12,}"
            + ")\\b"
    );
    private static final Pattern JWT_PATTERN = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{8,}\\b"
    );
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "(?is)-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----.*?"
            + "-----END(?: [A-Z0-9]+)* PRIVATE KEY-----"
    );
    private static final Pattern LINE_BREAK_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");
    private static final Pattern REDACTED_LINE_PREFIX_PATTERN = Pattern.compile(
        "^(?:[+ ]|-(?=-----END)|-(?!--{4})|L\\d+:\\s*)"
    );

    private SensitiveTextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return sanitizePreservingWhitespace(normalized);
    }

    public static String sanitizePreservingWhitespace(String value) {
        return sanitizePreservingWhitespace(value, SENSITIVE_ASSIGNMENT_PATTERN);
    }

    public static String sanitizeSourceCodePreservingWhitespace(String value) {
        return sanitizePreservingWhitespace(value, SENSITIVE_QUOTED_ASSIGNMENT_PATTERN);
    }

    private static String sanitizePreservingWhitespace(String value, Pattern assignmentPattern) {
        if (value == null) {
            return null;
        }
        String sanitized = PRIVATE_KEY_PATTERN.matcher(value)
            .replaceAll(SensitiveTextSanitizer::maskPrivateKeyBlock);
        sanitized = JDBC_URL_PATTERN.matcher(sanitized).replaceAll("jdbc:****");
        sanitized = URI_CREDENTIAL_PATTERN.matcher(sanitized).replaceAll("$1****$2");
        sanitized = SENSITIVE_QUERY_PARAM_PATTERN.matcher(sanitized).replaceAll("$1****");
        sanitized = assignmentPattern.matcher(sanitized).replaceAll(SensitiveTextSanitizer::maskSensitiveValue);
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1****");
        sanitized = OPENAI_STYLE_API_KEY_PATTERN.matcher(sanitized).replaceAll("sk-****");
        sanitized = KNOWN_CREDENTIAL_PATTERN.matcher(sanitized).replaceAll("[REDACTED CREDENTIAL]");
        return JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED JWT]");
    }

    private static String maskSensitiveValue(MatchResult match) {
        String value = match.group(2);
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return match.group(1) + "\"****\"";
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return match.group(1) + "'****'";
        }
        return match.group(1) + "****";
    }

    private static String maskPrivateKeyBlock(MatchResult match) {
        String block = match.group();
        StringBuilder sanitized = new StringBuilder(block.length());
        Matcher lineBreakMatcher = LINE_BREAK_PATTERN.matcher(block);
        int lineStart = 0;
        boolean firstLine = true;
        while (lineBreakMatcher.find()) {
            sanitized.append(maskPrivateKeyLine(
                block.substring(lineStart, lineBreakMatcher.start()),
                firstLine
            ));
            sanitized.append(lineBreakMatcher.group());
            lineStart = lineBreakMatcher.end();
            firstLine = false;
        }
        sanitized.append(maskPrivateKeyLine(block.substring(lineStart), firstLine));
        return sanitized.toString();
    }

    private static String maskPrivateKeyLine(String line, boolean firstLine) {
        Matcher prefixMatcher = REDACTED_LINE_PREFIX_PATTERN.matcher(line);
        String prefix = !firstLine && prefixMatcher.find() ? prefixMatcher.group() : "";
        return prefix + "[REDACTED PRIVATE KEY]";
    }
}
