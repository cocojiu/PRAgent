package com.repoguard.agent.review;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class SemanticDiffScopeExtractor {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+[^@]*@@\\s*(.*)$");
    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "\\b(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+"
            + "[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("
    );
    private static final Pattern YAML_KEY = Pattern.compile("^[+-]?\\s*([A-Za-z0-9_.-]+)\\s*:");
    private static final List<ScopeStrategy> STRATEGIES = List.of(
        new JavaLikeScopeStrategy(),
        new SqlScopeStrategy(),
        new ConfigScopeStrategy(),
        new HunkScopeStrategy()
    );

    String scope(String path, String patch, SemanticDiffPathClassifier pathClassifier) {
        SemanticDiffPathClassifier classifier = Objects.requireNonNull(pathClassifier, "pathClassifier");
        return STRATEGIES.stream()
            .filter(strategy -> strategy.supports(path, classifier))
            .findFirst()
            .orElseThrow()
            .scope(patch);
    }

    private interface ScopeStrategy {

        boolean supports(String path, SemanticDiffPathClassifier pathClassifier);

        String scope(String patch);
    }

    private static final class JavaLikeScopeStrategy implements ScopeStrategy {

        @Override
        public boolean supports(String path, SemanticDiffPathClassifier pathClassifier) {
            return pathClassifier.isJavaLike(path);
        }

        @Override
        public String scope(String patch) {
            return firstMatch(patch, JAVA_TYPE, 2)
                .or(() -> firstMatch(patch, JAVA_METHOD, 1))
                .orElse(hunkContext(patch));
        }
    }

    private static final class SqlScopeStrategy implements ScopeStrategy {

        @Override
        public boolean supports(String path, SemanticDiffPathClassifier pathClassifier) {
            return path.endsWith(".sql");
        }

        @Override
        public String scope(String patch) {
            if (patch == null) {
                return "file";
            }
            for (String line : patch.split("\\R")) {
                String normalized = stripPatchMarker(line).trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("create ")) {
                    return "create";
                }
                if (normalized.startsWith("alter ")) {
                    return "alter";
                }
                if (normalized.startsWith("drop ")) {
                    return "drop";
                }
                if (normalized.startsWith("insert ")) {
                    return "insert";
                }
                if (normalized.startsWith("update ")) {
                    return "update";
                }
                if (normalized.startsWith("delete ")) {
                    return "delete";
                }
            }
            return hunkContext(patch);
        }
    }

    private static final class ConfigScopeStrategy implements ScopeStrategy {

        @Override
        public boolean supports(String path, SemanticDiffPathClassifier pathClassifier) {
            return pathClassifier.isConfigLike(path);
        }

        @Override
        public String scope(String patch) {
            if (patch == null) {
                return "file";
            }
            for (String line : patch.split("\\R")) {
                Matcher matcher = YAML_KEY.matcher(stripPatchMarker(line));
                if (matcher.find()) {
                    return sanitizeKey(matcher.group(1));
                }
            }
            return hunkContext(patch);
        }
    }

    private static final class HunkScopeStrategy implements ScopeStrategy {

        @Override
        public boolean supports(String path, SemanticDiffPathClassifier pathClassifier) {
            return true;
        }

        @Override
        public String scope(String patch) {
            return hunkContext(patch);
        }
    }

    private static Optional<String> firstMatch(String patch, Pattern pattern, int group) {
        if (patch == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(patch);
        if (matcher.find()) {
            return Optional.of(sanitizeKey(matcher.group(group)));
        }
        return Optional.empty();
    }

    private static String hunkContext(String patch) {
        if (patch != null) {
            Matcher matcher = HUNK_HEADER.matcher(patch);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                return sanitizeKey(matcher.group(1));
            }
        }
        return "file";
    }

    private static String stripPatchMarker(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        char marker = line.charAt(0);
        if (marker == '+' || marker == '-' || marker == ' ') {
            return line.substring(1);
        }
        return line;
    }

    private static String sanitizeKey(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_$./-]+", "_")
            .replaceAll("_+", "_");
        return sanitized.isBlank() ? "file" : sanitized;
    }
}
