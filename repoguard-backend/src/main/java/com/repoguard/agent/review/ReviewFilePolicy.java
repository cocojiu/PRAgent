package com.repoguard.agent.review;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    private final Cache<String, FileClassification> fileClassifications = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build();

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
        return classification(filePath).excluded();
    }

    public boolean nonProduction(String filePath) {
        return classification(filePath).nonProduction();
    }

    public boolean approvedMessagePublisher(String filePath) {
        return classification(filePath).approvedMessagePublisher();
    }

    public boolean approvedGithubPublisher(String filePath) {
        return classification(filePath).approvedGithubPublisher();
    }

    public boolean approvedAuthorizationBoundary(String filePath) {
        return classification(filePath).approvedAuthorizationBoundary();
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

    private FileClassification classification(String filePath) {
        String normalizedPath = ReviewRuleApplicability.normalizePath(filePath);
        return fileClassifications.get(normalizedPath, this::classify);
    }

    private FileClassification classify(String normalizedPath) {
        boolean excluded = matchesAny(normalizedPath, excludedPathPatterns);
        return new FileClassification(
            excluded,
            excluded || matchesAny(normalizedPath, nonProductionPathPatterns),
            matchesAny(normalizedPath, approvedMessagePublisherPatterns),
            matchesAny(normalizedPath, approvedGithubPublisherPatterns),
            matchesAny(normalizedPath, approvedAuthorizationBoundaryPatterns)
        );
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

    private record FileClassification(
        boolean excluded,
        boolean nonProduction,
        boolean approvedMessagePublisher,
        boolean approvedGithubPublisher,
        boolean approvedAuthorizationBoundary
    ) {
    }
}
