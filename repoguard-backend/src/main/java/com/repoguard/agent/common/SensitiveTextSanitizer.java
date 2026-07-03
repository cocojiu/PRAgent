package com.repoguard.agent.common;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public final class SensitiveTextSanitizer {

    private static final Pattern URI_CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://[^\\s/:@]+:)[^\\s/@]+(@)"
    );
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile(
        "(?i)([?&](?:access_token|token|secret|password|key|sign)=)[^\\s&\"']+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)([\"']?\\b(?:[\\w.-]*(?:access[_-]?token|token|password|secret|sign)[\\w.-]*"
            + "|api[-_ ]?key|key)\\b[\"']?\\s*[:=]\\s*)"
            + "(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)"
    );
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
        "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"
    );

    private SensitiveTextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        String sanitized = URI_CREDENTIAL_PATTERN.matcher(normalized).replaceAll("$1****$2");
        sanitized = SENSITIVE_QUERY_PARAM_PATTERN.matcher(sanitized).replaceAll("$1****");
        sanitized = SENSITIVE_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll(SensitiveTextSanitizer::maskSensitiveValue);
        return BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1****");
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
}
