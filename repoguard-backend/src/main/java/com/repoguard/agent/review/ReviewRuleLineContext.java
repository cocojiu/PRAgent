package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;

record ReviewRuleLineContext(
    String filePath,
    int lineNumber,
    String line,
    String trimmedLine,
    Map<String, ReviewRuleSettings> configuredRules,
    boolean patchHasAuthorizationGuard,
    ChangedFileContext changedFileContext,
    String patch
) {

    ReviewRuleLineContext {
        changedFileContext = changedFileContext == null
            ? ChangedFileContext.notRequested(filePath)
            : changedFileContext;
    }

    ReviewRuleLineContext(
        String filePath,
        int lineNumber,
        String line,
        String trimmedLine,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        this(
            filePath,
            lineNumber,
            line,
            trimmedLine,
            configuredRules,
            false,
            ChangedFileContext.notRequested(filePath),
            line
        );
    }

    ReviewRuleLineContext(
        String filePath,
        int lineNumber,
        String line,
        String trimmedLine,
        Map<String, ReviewRuleSettings> configuredRules,
        boolean patchHasAuthorizationGuard
    ) {
        this(
            filePath,
            lineNumber,
            line,
            trimmedLine,
            configuredRules,
            patchHasAuthorizationGuard,
            ChangedFileContext.notRequested(filePath),
            line
        );
    }

    boolean isApplicable(String ruleId) {
        return ReviewRuleApplicability.isApplicable(ruleId, filePath, configuredRules);
    }

    String normalizePath(String value) {
        return ReviewRuleApplicability.normalizePath(value);
    }

    boolean fullContextAvailable() {
        return changedFileContext.available();
    }

    boolean contextualEvidenceVerified() {
        return !changedFileContext.missingAfterRequest();
    }

    String analysisSource() {
        if (changedFileContext.available()) {
            return changedFileContext.content();
        }
        return PatchSourceExtractor.afterImage(patch, line);
    }
}
