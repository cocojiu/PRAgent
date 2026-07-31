package com.repoguard.agent.review;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlMigrationAnalyzer {

    private static final String IDENTIFIER = "[`\"A-Za-z0-9_$.]+";
    private static final Pattern CREATE_TABLE = Pattern.compile(
        "(?is)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(" + IDENTIFIER + ")"
    );
    private static final Pattern DROP_TABLE = Pattern.compile(
        "(?is)\\bdrop\\s+table\\s+(?:if\\s+exists\\s+)?(" + IDENTIFIER + ")"
    );
    private static final Pattern ALTER_DROP_COLUMN = Pattern.compile(
        "(?is)\\balter\\s+table\\s+(" + IDENTIFIER + ")\\s+drop\\s+column\\s+(" + IDENTIFIER + ")"
    );
    private static final Pattern STANDALONE_DROP_COLUMN = Pattern.compile(
        "(?is)\\bdrop\\s+column\\s+(" + IDENTIFIER + ")"
    );
    private static final Pattern TRUNCATE_TABLE = Pattern.compile(
        "(?is)\\btruncate\\s+(?:table\\s+)?(" + IDENTIFIER + ")"
    );
    private static final Pattern ADD_REQUIRED_COLUMN = Pattern.compile(
        "(?is)\\balter\\s+table\\s+(" + IDENTIFIER + ")\\s+add\\s+(?:column\\s+)?"
            + "(" + IDENTIFIER + ")\\s+(.+?)\\bnot\\s+null\\b"
    );
    private static final Pattern DEFAULT_CLAUSE = Pattern.compile("(?is)\\bdefault\\b");

    boolean destructiveStatement(ReviewRuleLineContext context) {
        String sanitizedLine = sanitize(context.trimmedLine());
        if (!containsDestructiveKeyword(sanitizedLine)) {
            return false;
        }
        String sanitizedSource = sanitize(context.analysisSource());
        String statement = statementFor(context, sanitizedSource, sanitizedLine);
        Set<String> createdTables = createdTables(sanitizedSource);

        Matcher dropTable = DROP_TABLE.matcher(statement);
        if (dropTable.find()) {
            return !createdTables.contains(identifier(dropTable.group(1)));
        }
        Matcher truncate = TRUNCATE_TABLE.matcher(statement);
        if (truncate.find()) {
            return !createdTables.contains(identifier(truncate.group(1)));
        }
        return ALTER_DROP_COLUMN.matcher(statement).find()
            || STANDALONE_DROP_COLUMN.matcher(statement).find();
    }

    boolean requiredColumnWithoutCompatibilityWindow(ReviewRuleLineContext context) {
        String sanitizedLine = sanitize(context.trimmedLine());
        if (!sanitizedLine.toLowerCase(Locale.ROOT).contains("not null")) {
            return false;
        }
        String sanitizedSource = sanitize(context.analysisSource());
        String statement = statementFor(context, sanitizedSource, sanitizedLine);
        Matcher addColumn = ADD_REQUIRED_COLUMN.matcher(statement);
        if (!addColumn.find()) {
            return false;
        }
        String column = identifier(addColumn.group(2));
        if ("constraint".equals(column) || "index".equals(column) || "key".equals(column)) {
            return false;
        }
        if (DEFAULT_CLAUSE.matcher(statement).find()) {
            return false;
        }
        return !createdTables(sanitizedSource).contains(identifier(addColumn.group(1)));
    }

    private boolean containsDestructiveKeyword(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\bdrop\\s+table\\b.*")
            || normalized.matches("(?s).*\\bdrop\\s+column\\b.*")
            || normalized.matches("(?s).*\\btruncate\\s+(?:table\\s+)?[" + "`\"a-z0-9_" + "].*");
    }

    private Set<String> createdTables(String source) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = CREATE_TABLE.matcher(source);
        while (matcher.find()) {
            tables.add(identifier(matcher.group(1)));
        }
        return Set.copyOf(tables);
    }

    private String statementFor(
        ReviewRuleLineContext context,
        String source,
        String sanitizedLine
    ) {
        if (source.isBlank()) {
            return sanitizedLine;
        }
        int anchor = context.fullContextAvailable()
            ? lineOffset(source, context.lineNumber())
            : -1;
        if (anchor < 0 || anchor >= source.length()) {
            anchor = source.toLowerCase(Locale.ROOT).indexOf(sanitizedLine.trim().toLowerCase(Locale.ROOT));
        }
        if (anchor < 0) {
            return sanitizedLine;
        }
        int start = source.lastIndexOf(';', anchor);
        int end = source.indexOf(';', anchor);
        return source.substring(start < 0 ? 0 : start + 1, end < 0 ? source.length() : end + 1);
    }

    private int lineOffset(String source, int lineNumber) {
        if (lineNumber <= 1) {
            return 0;
        }
        int currentLine = 1;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n' && ++currentLine == lineNumber) {
                return index + 1;
            }
        }
        return -1;
    }

    private String identifier(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "")
            .replace("\"", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private String sanitize(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean singleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(current);
                } else {
                    result.append(' ');
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    blockComment = false;
                    index++;
                } else {
                    result.append(current == '\n' || current == '\r' ? current : ' ');
                }
            } else if (singleQuoted) {
                if (escaped) {
                    escaped = false;
                    result.append(' ');
                } else if (current == '\\') {
                    escaped = true;
                    result.append(' ');
                } else if (current == '\'' && next == '\'') {
                    result.append("  ");
                    index++;
                } else if (current == '\'') {
                    singleQuoted = false;
                    result.append(' ');
                } else {
                    result.append(current == '\n' || current == '\r' ? current : ' ');
                }
            } else if (current == '-' && next == '-') {
                result.append("  ");
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                result.append("  ");
                blockComment = true;
                index++;
            } else if (current == '\'') {
                result.append(' ');
                singleQuoted = true;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
