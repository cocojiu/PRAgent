package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.List;
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

    String semanticKey(GithubChangedFile file, String patch) {
        String path = normalizedPath(file);
        String scope = semanticScope(path, patch);
        return semanticDomain(path) + ":" + moduleKey(path) + ":" + scope;
    }

    String chunkGroupKey(GithubChangedFile file) {
        String path = normalizedPath(file);
        return semanticDomain(path) + ":" + moduleKey(path);
    }

    String semanticReason(GithubChangedFile file, String patch) {
        String path = normalizedPath(file);
        if (isTestPath(path)) {
            return "test_scope";
        }
        if (isJavaLike(path)) {
            return "code_scope";
        }
        if (path.endsWith(".sql")) {
            return "sql_statement";
        }
        if (isConfigLike(path)) {
            return "config_section";
        }
        if (path.endsWith(".md")) {
            return "documentation_section";
        }
        return "path_scope";
    }

    private String semanticScope(String path, String patch) {
        if (isJavaLike(path)) {
            return firstMatch(patch, JAVA_TYPE, 2)
                .or(() -> firstMatch(patch, JAVA_METHOD, 1))
                .orElse(hunkContext(patch));
        }
        if (path.endsWith(".sql")) {
            return firstSqlVerb(patch);
        }
        if (isConfigLike(path)) {
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

    private String semanticDomain(String path) {
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            return "database";
        }
        if (isConfigLike(path)) {
            return "config";
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            return "delivery";
        }
        if (isTestPath(path)) {
            return "test";
        }
        if (path.endsWith(".md")) {
            return "docs";
        }
        return "source";
    }

    private String moduleKey(String path) {
        if (path.contains("/src/main/java/")) {
            return after(path, "/src/main/java/", 3);
        }
        if (path.contains("/src/test/java/")) {
            return after(path, "/src/test/java/", 3);
        }
        if (path.contains("/src/main/resources/")) {
            return after(path, "/src/main/resources/", 2);
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private String after(String path, String marker, int depth) {
        String suffix = path.substring(path.indexOf(marker) + marker.length());
        String[] parts = suffix.split("/");
        List<String> selected = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                selected.add(part);
            }
            if (selected.size() == depth) {
                break;
            }
        }
        return selected.isEmpty() ? "root" : String.join("/", selected);
    }

    private boolean isJavaLike(String path) {
        return path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy");
    }

    private boolean isConfigLike(String path) {
        return path.endsWith(".yml")
            || path.endsWith(".yaml")
            || path.endsWith(".properties")
            || path.endsWith(".json")
            || path.contains("config");
    }

    private boolean isTestPath(String path) {
        return path.contains("/src/test/") || path.endsWith("test.java") || path.endsWith("spec.ts");
    }

    private String normalizedPath(GithubChangedFile file) {
        return file.filename() == null ? "" : file.filename().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String sanitizeKey(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_$./-]+", "_")
            .replaceAll("_+", "_");
        return sanitized.isBlank() ? "file" : sanitized;
    }
}
