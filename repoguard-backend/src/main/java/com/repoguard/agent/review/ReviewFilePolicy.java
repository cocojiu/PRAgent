package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewContextProperties;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewFilePolicy {

    private static final Set<String> CONTEXTUAL_EXTENSIONS = Set.of(
        ".java",
        ".kt",
        ".kts",
        ".sql",
        ".go",
        ".py",
        ".rb",
        ".rs",
        ".cs",
        ".c",
        ".cc",
        ".cpp",
        ".h",
        ".hpp",
        ".js",
        ".jsx",
        ".ts",
        ".tsx",
        ".vue",
        ".scala",
        ".groovy",
        ".yml",
        ".yaml",
        ".properties",
        ".toml",
        ".xml",
        ".json",
        ".conf",
        ".ini"
    );

    private final List<String> excludedPathPatterns;
    private final List<String> nonProductionPathPatterns;
    private final List<String> approvedMessagePublisherPatterns;
    private final List<String> approvedGithubPublisherPatterns;
    private final List<String> approvedAuthorizationBoundaryPatterns;
    private final Set<String> approvedRedactionMethods;

    public ReviewFilePolicy(ReviewContextProperties properties) {
        ReviewContextProperties required = Objects.requireNonNull(properties, "properties");
        this.excludedPathPatterns = immutablePatterns(required.getExcludedPathPatterns());
        this.nonProductionPathPatterns = immutablePatterns(required.getNonProductionPathPatterns());
        this.approvedMessagePublisherPatterns = immutablePatterns(required.getApprovedMessagePublisherPatterns());
        this.approvedGithubPublisherPatterns = immutablePatterns(required.getApprovedGithubPublisherPatterns());
        this.approvedAuthorizationBoundaryPatterns = immutablePatterns(
            required.getApprovedAuthorizationBoundaryPatterns()
        );
        this.approvedRedactionMethods = required.getApprovedRedactionMethods().stream()
            .filter(StringUtils::hasText)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    static ReviewFilePolicy defaults() {
        return new ReviewFilePolicy(new ReviewContextProperties());
    }

    public boolean excluded(String filePath) {
        return matchesAny(filePath, excludedPathPatterns);
    }

    public boolean nonProduction(String filePath) {
        return excluded(filePath) || matchesAny(filePath, nonProductionPathPatterns);
    }

    public boolean approvedMessagePublisher(String filePath) {
        return matchesAny(filePath, approvedMessagePublisherPatterns);
    }

    public boolean approvedGithubPublisher(String filePath) {
        return matchesAny(filePath, approvedGithubPublisherPatterns);
    }

    public boolean approvedAuthorizationBoundary(String filePath) {
        return matchesAny(filePath, approvedAuthorizationBoundaryPatterns);
    }

    public boolean approvedRedactionExpression(String expression) {
        if (!StringUtils.hasText(expression)) {
            return false;
        }
        String normalized = expression.toLowerCase(Locale.ROOT);
        if (normalized.matches(
            ".*\\b\\w*(mask(ed)?|redact(ed)?|sanitiz(ed)?|hash(ed)?|fingerprint(ed)?|summary)\\w*\\b.*"
        )) {
            return true;
        }
        return approvedRedactionMethods.stream()
            .anyMatch(method -> normalized.matches(
                "(?s).*(?:\\.|\\b)" + java.util.regex.Pattern.quote(method) + "\\s*\\(.*"
            ));
    }

    public boolean requiresFullFileContext(PullRequestChangedFile file) {
        if (file == null || excluded(file.filename())) {
            return false;
        }
        String path = ReviewRuleApplicability.normalizePath(file.filename());
        if (nonProduction(file.filename()) && !testContextCandidate(path)) {
            return false;
        }
        return CONTEXTUAL_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    private boolean testContextCandidate(String normalizedPath) {
        return normalizedPath.matches("(?:^|.*/)(?:src/)?(?:test|tests|it)(?:/.*)?")
            || normalizedPath.matches(".*(?:test|tests|spec|it)\\.[^.]+$");
    }

    private boolean matchesAny(String filePath, List<String> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.startsWith("^")
            ? ReviewRuleApplicability.matchesAnchoredPathPattern(filePath, pattern.substring(1))
            : ReviewRuleApplicability.matchesPathPattern(filePath, pattern));
    }

    private List<String> immutablePatterns(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }
}
