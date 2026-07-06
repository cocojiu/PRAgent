package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SemanticDiffScopeResolver {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+[^@]*@@\\s*(.*)$");
    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "\\b(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+"
            + "[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("
    );
    private static final Pattern YAML_KEY = Pattern.compile("^[+-]?\\s*([A-Za-z0-9_.-]+)\\s*:");

    private final SemanticDiffPathClassifier pathClassifier;

    SemanticDiffScopeResolver() {
        this(new SemanticDiffPathClassifier());
    }

    SemanticDiffScopeResolver(SemanticDiffPathClassifier pathClassifier) {
        this.pathClassifier = pathClassifier == null ? new SemanticDiffPathClassifier() : pathClassifier;
    }

    String semanticKey(GithubChangedFile file, String patch) {
        String path = pathClassifier.normalizedPath(file);
        String scope = semanticScope(path, patch);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path) + ":" + scope;
    }

    String chunkGroupKey(GithubChangedFile file) {
        String path = pathClassifier.normalizedPath(file);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path);
    }

    String semanticReason(GithubChangedFile file, String patch) {
        return pathClassifier.semanticReason(pathClassifier.normalizedPath(file));
    }

    private String semanticScope(String path, String patch) {
        if (pathClassifier.isJavaLike(path)) {
            return firstMatch(patch, JAVA_TYPE, 2)
                .or(() -> firstMatch(patch, JAVA_METHOD, 1))
                .orElse(hunkContext(patch));
        }
        if (path.endsWith(".sql")) {
            return firstSqlVerb(patch);
        }
        if (pathClassifier.isConfigLike(path)) {
            return firstConfigKey(patch).orElse(hunkContext(patch));
        }
        return hunkContext(patch);
    }

    private Optional<String> firstConfigKey(String patch) {
        if (patch == null) {
            return Optional.empty();
        }
        for (String line : patch.split("\\R")) {
            Matcher matcher = YAML_KEY.matcher(stripPatchMarker(line));
            if (matcher.find()) {
                return Optional.of(sanitizeKey(matcher.group(1)));
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstMatch(String patch, Pattern pattern, int group) {
        if (patch == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(patch);
        if (matcher.find()) {
            return Optional.of(sanitizeKey(matcher.group(group)));
        }
        return Optional.empty();
    }

    private String firstSqlVerb(String patch) {
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

    private String hunkContext(String patch) {
        if (patch != null) {
            Matcher matcher = HUNK_HEADER.matcher(patch);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                return sanitizeKey(matcher.group(1));
            }
        }
        return "file";
    }

    private String stripPatchMarker(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        char marker = line.charAt(0);
        if (marker == '+' || marker == '-' || marker == ' ') {
            return line.substring(1);
        }
        return line;
    }

    private String sanitizeKey(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_$./-]+", "_")
            .replaceAll("_+", "_");
        return sanitized.isBlank() ? "file" : sanitized;
    }
}
