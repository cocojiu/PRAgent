package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class SemanticDiffPathClassifier {

    String semanticDomain(String path) {
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

    String moduleKey(String path) {
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

    String semanticReason(String path) {
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

    boolean isJavaLike(String path) {
        return path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy");
    }

    boolean isConfigLike(String path) {
        return path.endsWith(".yml")
            || path.endsWith(".yaml")
            || path.endsWith(".properties")
            || path.endsWith(".json")
            || path.contains("config");
    }

    String normalizedPath(PullRequestChangedFile file) {
        return file.filename() == null ? "" : file.filename().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private boolean isTestPath(String path) {
        return path.contains("/src/test/") || path.endsWith("test.java") || path.endsWith("spec.ts");
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
}
