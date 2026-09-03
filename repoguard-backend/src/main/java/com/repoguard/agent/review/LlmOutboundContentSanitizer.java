package com.repoguard.agent.review;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class LlmOutboundContentSanitizer {

    static final String SENSITIVE_PATH_PLACEHOLDER = "[content omitted: sensitive path]";

    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
        ".env",
        ".npmrc",
        ".pypirc",
        ".netrc",
        "credentials",
        "credentials.json",
        "service-account.json",
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519"
    );
    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
        ".pem",
        ".key",
        ".p12",
        ".pfx",
        ".jks",
        ".keystore"
    );
    private static final Set<String> SOURCE_CODE_EXTENSIONS = Set.of(
        ".java",
        ".kt",
        ".kts",
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
        ".groovy"
    );

    private final ReviewFilePolicy filePolicy;

    LlmOutboundContentSanitizer() {
        this(ReviewFilePolicy.defaults());
    }

    @Autowired
    LlmOutboundContentSanitizer(ReviewFilePolicy filePolicy) {
        this.filePolicy = Objects.requireNonNull(filePolicy, "filePolicy");
    }

    String sanitizePatch(String filePath, String patch) {
        if (patch == null) {
            return null;
        }
        if (filePolicy.excluded(filePath) || sensitivePath(filePath)) {
            return SENSITIVE_PATH_PLACEHOLDER;
        }
        return sanitizeRepositoryContent(filePath, patch);
    }

    String sanitizeMultiline(String value) {
        return SensitiveTextSanitizer.sanitizePreservingWhitespace(value);
    }

    String sanitizeContext(LlmReviewContext context, PullRequestDiff diff) {
        if (context == null) {
            return "";
        }
        LlmReviewContext sanitized = new LlmReviewContext(
            context.slices().stream().map(this::sanitizeSlice).toList(),
            sanitizeMultiline(context.rulePolicyContext()),
            context.limitations().stream().map(limitation -> new LlmReviewContext.ContextLimitation(
                sanitizeInline(limitation.filePath()),
                sanitizeInline(limitation.status()),
                sanitizeInline(limitation.reason())
            )).toList(),
            context.budgetTruncated(),
            context.maxTotalChars(),
            context.maxRelatedFiles(),
            sanitizeInline(context.repositoryContextSummary())
        );
        return sanitized.renderFor(diff);
    }

    String sanitizeInline(String value) {
        return SensitiveTextSanitizer.sanitize(value);
    }

    private LlmContextSlice sanitizeSlice(LlmContextSlice slice) {
        boolean omitContent = filePolicy.excluded(slice.filePath()) || sensitivePath(slice.filePath());
        return new LlmContextSlice(
            sanitizeInline(slice.filePath()),
            slice.startLine(),
            slice.endLine(),
            slice.role(),
            omitContent ? SENSITIVE_PATH_PLACEHOLDER : sanitizeRepositoryContent(
                slice.filePath(),
                slice.numberedContent()
            ),
            omitContent ? Set.of() : slice.symbols(),
            slice.riskPriority()
        );
    }

    private boolean sensitivePath(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return false;
        }
        String normalized = filePath.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (SENSITIVE_FILE_NAMES.contains(fileName) || fileName.startsWith(".env.")) {
            return true;
        }
        if (normalized.contains("/.ssh/") || normalized.endsWith("/.aws/credentials")) {
            return true;
        }
        return SENSITIVE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private String sanitizeRepositoryContent(String filePath, String value) {
        if (sourceCodePath(filePath)) {
            return SensitiveTextSanitizer.sanitizeSourceCodePreservingWhitespace(value);
        }
        return sanitizeMultiline(value);
    }

    private boolean sourceCodePath(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return false;
        }
        String normalized = filePath.trim().toLowerCase(Locale.ROOT);
        return SOURCE_CODE_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }
}
