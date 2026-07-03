package com.repoguard.agent.common;

import java.util.regex.Pattern;

public final class SensitiveTextSanitizer {

    private static final Pattern URI_CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://[^\\s/:@]+:)[^\\s/@]+(@)"
    );
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile(
        "(?i)([?&](?:access_token|token|secret|password|key|sign)=)[^\\s&\"']+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)(\\b(?:access[_-]?token|token|password|secret|api[-_ ]?key|key|sign)\\b\\s*[:=]\\s*)"
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
        sanitized = SENSITIVE_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1****");
        return BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1****");
    }
}
