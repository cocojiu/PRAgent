package com.repoguard.agent.review;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class LlmSourceContextSlicer {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
        "\\b(?:class|interface|record|enum|object|struct|trait)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern METHOD_OR_TYPE_START = Pattern.compile(
        "(?i).*(?:class|interface|record|enum|fun|function|def|public|protected|private|internal|static|async)\\b.*[{:].*"
    );

    private final int maxSliceChars;

    LlmSourceContextSlicer(LlmReviewContextProperties properties) {
        this.maxSliceChars = properties.getMaxSliceChars();
    }

    LlmContextSlice slice(PullRequestChangedFile file, int riskPriority) {
        ChangedFileContext context = file.context();
        String content = context == null ? "" : context.content();
        List<String> lines = content.lines().toList();
        if (lines.isEmpty()) {
            return null;
        }
        LineRange anchors = addedLineRange(file.patch(), lines.size());
        LineRange range = selectRange(lines, anchors);
        String numbered = renderNumbered(lines, range);
        return new LlmContextSlice(
            file.filename(),
            range.start(),
            range.end(),
            role(file.filename(), content),
            numbered,
            symbols(file.filename(), content),
            riskPriority
        );
    }

    private LineRange selectRange(List<String> lines, LineRange anchors) {
        String whole = String.join("\n", lines);
        if (whole.length() <= maxSliceChars) {
            return new LineRange(1, lines.size());
        }
        int start = Math.max(1, anchors.start() - 30);
        int lowerBound = Math.max(1, anchors.start() - 100);
        for (int line = anchors.start(); line >= lowerBound; line--) {
            String candidate = lines.get(line - 1).trim();
            if (METHOD_OR_TYPE_START.matcher(candidate).matches()) {
                start = line;
                break;
            }
        }
        int end = findScopeEnd(lines, start, Math.max(anchors.end(), start));
        if (end <= start) {
            end = Math.min(lines.size(), anchors.end() + 40);
        }
        return trimToBudget(lines, new LineRange(start, end));
    }

    private int findScopeEnd(List<String> lines, int start, int minimumEnd) {
        int balance = 0;
        boolean opened = false;
        for (int line = start; line <= lines.size(); line++) {
            String value = lines.get(line - 1);
            balance += count(value, '{');
            balance -= count(value, '}');
            opened = opened || value.indexOf('{') >= 0;
            if (opened && line >= minimumEnd && balance <= 0) {
                return line;
            }
            if (line - start >= 240) {
                return line;
            }
        }
        return lines.size();
    }

    private LineRange trimToBudget(List<String> lines, LineRange requested) {
        int chars = 0;
        int end = requested.start();
        for (int line = requested.start(); line <= requested.end(); line++) {
            int next = lines.get(line - 1).length() + 16;
            if (line > requested.start() && chars + next > maxSliceChars) {
                break;
            }
            chars += next;
            end = line;
        }
        return new LineRange(requested.start(), end);
    }

    private String renderNumbered(List<String> lines, LineRange range) {
        StringBuilder rendered = new StringBuilder();
        for (int line = range.start(); line <= range.end(); line++) {
            if (!rendered.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append('L').append(line).append(": ").append(lines.get(line - 1));
        }
        return rendered.toString();
    }

    private LineRange addedLineRange(String patch, int lineCount) {
        if (!StringUtils.hasText(patch)) {
            return new LineRange(1, Math.min(lineCount, 1));
        }
        int minimum = Integer.MAX_VALUE;
        int maximum = 0;
        int current = 0;
        for (String line : patch.split("\\R")) {
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                current = Integer.parseInt(header.group(1));
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                minimum = Math.min(minimum, current);
                maximum = Math.max(maximum, current);
                current++;
            } else if (!line.startsWith("-") || line.startsWith("---")) {
                current++;
            }
        }
        if (minimum == Integer.MAX_VALUE) {
            return new LineRange(1, Math.min(lineCount, 1));
        }
        return new LineRange(Math.max(1, minimum), Math.min(lineCount, Math.max(minimum, maximum)));
    }

    private LlmContextSlice.Role role(String path, String content) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(?:^|/)(?:src/)?(?:test|tests|it)(?:/|$).*")
            || normalized.matches(".*(?:test|tests|spec|it)\\.[^.]+$")) {
            return LlmContextSlice.Role.TEST;
        }
        if (normalized.matches(".*\\.(?:ya?ml|properties|toml|json|xml|conf|ini)$")) {
            return LlmContextSlice.Role.CONFIG;
        }
        if (content.matches("(?s).*\\binterface\\s+[A-Za-z_$][A-Za-z0-9_$]*.*")) {
            return LlmContextSlice.Role.INTERFACE;
        }
        return LlmContextSlice.Role.SOURCE;
    }

    private Set<String> symbols(String path, String content) {
        Set<String> symbols = new LinkedHashSet<>();
        Matcher matcher = TYPE_DECLARATION.matcher(content);
        while (matcher.find() && symbols.size() < 12) {
            symbols.add(matcher.group(1));
        }
        String filename = path == null ? "" : path.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        int dot = filename.lastIndexOf('.');
        if (dot > slash + 1) {
            String basename = filename.substring(slash + 1, dot)
                .replaceFirst("(?:Tests?|IT|Spec)$", "")
                .replaceFirst("Impl$", "");
            if (!basename.isBlank()) {
                symbols.add(basename);
            }
        }
        return Set.copyOf(symbols);
    }

    private int count(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private record LineRange(int start, int end) {
    }
}
