package com.repoguard.agent.review;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validates the deliberately small, deterministic rule DSL. */
@Component
public class DeclarativeRulePolicy {

    public static final String BUILTIN = "BUILTIN";
    public static final String REGEX = "REGEX";
    public static final String AST = "AST";
    public static final int MAX_EXPRESSION_LENGTH = 1024;
    public static final int MAX_EXCEPTION_LENGTH = 1024;

    private static final Pattern AST_QUERY = Pattern.compile(
        "^(token|call):[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)?$"
    );
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern BACK_REFERENCE = Pattern.compile("\\\\[1-9][0-9]*");
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile(
        "(?:\\([^\\n]{0,256}[+*][^\\n]{0,256}\\)|\\[[^\\]]+\\][+*])[+*?]"
    );

    public String normalizeType(String value) {
        if (!StringUtils.hasText(value)) {
            return BUILTIN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!BUILTIN.equals(normalized) && !REGEX.equals(normalized) && !AST.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported declarative detector type: " + value);
        }
        return normalized;
    }

    public boolean isDeclarativeType(String value) {
        String normalized = normalizeType(value);
        return REGEX.equals(normalized) || AST.equals(normalized);
    }

    public String detectorVersion(String type) {
        return switch (normalizeType(type)) {
            case REGEX -> "declarative-regex-v1";
            case AST -> "declarative-ast-v1";
            default -> "builtin-detector-v2";
        };
    }

    public void validate(String type, String expression, String exceptionPatterns) {
        String normalizedType = normalizeType(type);
        String normalizedExpression = expression == null ? "" : expression.trim();
        String normalizedExceptions = exceptionPatterns == null ? "" : exceptionPatterns.trim();
        if (normalizedExceptions.length() > MAX_EXCEPTION_LENGTH || hasUnsafeCharacters(normalizedExceptions)) {
            throw new IllegalArgumentException("Declarative rule exception patterns are invalid or too long");
        }
        if (BUILTIN.equals(normalizedType)) {
            if (!normalizedExpression.isEmpty() || !normalizedExceptions.isEmpty()) {
                throw new IllegalArgumentException("Built-in rules cannot define a declarative matcher");
            }
            return;
        }
        if (!StringUtils.hasText(normalizedExpression) || normalizedExpression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(
                "Declarative rule matcher expression is required and must be at most 1024 characters"
            );
        }
        if (hasUnsafeCharacters(normalizedExpression)) {
            throw new IllegalArgumentException("Declarative rule matcher contains control characters");
        }
        if (AST.equals(normalizedType)) {
            if (!AST_QUERY.matcher(normalizedExpression).matches()) {
                throw new IllegalArgumentException("AST matcher must use token:<name> or call:<name>");
            }
            return;
        }
        if (BACK_REFERENCE.matcher(normalizedExpression).find()
            || NESTED_QUANTIFIER.matcher(normalizedExpression).find()
            || normalizedExpression.contains("(?")) {
            throw new IllegalArgumentException("Regex matcher uses unsupported backtracking or lookaround syntax");
        }
        try {
            Pattern.compile(normalizedExpression);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Regex matcher expression is invalid", ex);
        }
    }

    private boolean hasUnsafeCharacters(String value) {
        return CONTROL_CHARACTER.matcher(value).find();
    }
}
