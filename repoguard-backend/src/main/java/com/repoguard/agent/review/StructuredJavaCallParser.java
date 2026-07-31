package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StructuredJavaCallParser {

    private StructuredJavaCallParser() {
    }

    static Optional<Invocation> invocation(String source, Pattern invocationPattern) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = invocationPattern.matcher(source);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int openParenthesis = source.indexOf('(', matcher.start());
        if (openParenthesis < 0) {
            return Optional.empty();
        }
        int closeParenthesis = matchingParenthesis(source, openParenthesis);
        if (closeParenthesis < 0) {
            return Optional.empty();
        }
        String receiver = matcher.groupCount() >= 1 ? matcher.group(1) : "";
        String method = matcher.groupCount() >= 2 ? matcher.group(2) : "";
        return Optional.of(new Invocation(
            receiver,
            method,
            splitTopLevel(source.substring(openParenthesis + 1, closeParenthesis))
        ));
    }

    static String statementStartingAt(ReviewRuleLineContext context) {
        String source = context.analysisSource();
        if (source == null || source.isBlank()) {
            return context.trimmedLine();
        }
        int anchor = context.fullContextAvailable()
            ? lineOffset(source, context.lineNumber())
            : source.indexOf(context.line());
        if (anchor < 0) {
            anchor = source.indexOf(context.trimmedLine());
        }
        if (anchor < 0) {
            return context.trimmedLine();
        }
        int limit = Math.min(source.length(), anchor + 16_384);
        char quote = 0;
        boolean escaped = false;
        int parentheses = 0;
        for (int index = anchor; index < limit; index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '(') {
                parentheses++;
            } else if (current == ')') {
                parentheses = Math.max(0, parentheses - 1);
            } else if (current == ';' && parentheses == 0) {
                return source.substring(anchor, index + 1);
            }
        }
        return source.substring(anchor, limit);
    }

    static Optional<Assignment> assignment(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        int equals = assignmentEquals(source);
        if (equals < 0) {
            return Optional.empty();
        }
        String left = source.substring(0, equals).trim();
        String right = source.substring(equals + 1).trim().replaceFirst(";\\s*$", "");
        Matcher target = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*)\\s*$").matcher(left);
        if (!target.find()) {
            return Optional.empty();
        }
        return Optional.of(new Assignment(target.group(1), right));
    }

    static List<String> stringLiteralFragments(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        boolean escaped = false;
        char quote = 0;
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (quote == 0) {
                if (current == '"' || current == '\'') {
                    quote = current;
                    value.setLength(0);
                }
                continue;
            }
            if (escaped) {
                value.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                values.add(value.toString());
                quote = 0;
            } else {
                value.append(current);
            }
        }
        return List.copyOf(values);
    }

    static List<String> splitTopLevel(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '(' || current == '[' || current == '{' || current == '<') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}' || current == '>') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                values.add(arguments.substring(start, index).trim());
                start = index + 1;
            }
        }
        values.add(arguments.substring(start).trim());
        return values.stream().filter(value -> !value.isBlank()).toList();
    }

    private static int matchingParenthesis(String source, int opening) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = opening; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int lineOffset(String source, int lineNumber) {
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

    private static int assignmentEquals(String source) {
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth = Math.max(0, depth - 1);
            } else if (current == '=' && depth == 0) {
                char previous = index == 0 ? '\0' : source.charAt(index - 1);
                char next = index + 1 >= source.length() ? '\0' : source.charAt(index + 1);
                if (previous != '=' && previous != '!' && previous != '<' && previous != '>' && next != '=') {
                    return index;
                }
            }
        }
        return -1;
    }

    record Invocation(String receiver, String method, List<String> arguments) {
    }

    record Assignment(String target, String expression) {
    }
}
