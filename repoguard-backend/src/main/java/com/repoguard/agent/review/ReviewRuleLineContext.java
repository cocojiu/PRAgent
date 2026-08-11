package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import java.util.Set;

record ReviewRuleLineContext(
    String filePath,
    int lineNumber,
    String line,
    String trimmedLine,
    Map<String, ReviewRuleSettings> configuredRules,
    boolean patchHasAuthorizationGuard,
    ChangedFileContext changedFileContext,
    String patch,
    Set<String> applicableRuleIds
) {

    ReviewRuleLineContext {
        configuredRules = configuredRules == null ? Map.of() : configuredRules;
        changedFileContext = changedFileContext == null
            ? ChangedFileContext.notRequested(filePath)
            : changedFileContext;
        applicableRuleIds = applicableRuleIds == null
            ? ReviewRuleApplicability.applicableRuleIds(filePath, configuredRules)
            : Set.copyOf(applicableRuleIds);
    }

    ReviewRuleLineContext(
        String filePath,
        int lineNumber,
        String line,
        String trimmedLine,
        Map<String, ReviewRuleSettings> configuredRules,
        boolean patchHasAuthorizationGuard,
        ChangedFileContext changedFileContext,
        String patch
    ) {
        this(
            filePath,
            lineNumber,
            line,
            trimmedLine,
            configuredRules,
            patchHasAuthorizationGuard,
            changedFileContext,
            patch,
            null
        );
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
            line,
            null
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
            line,
            null
        );
    }

    boolean isApplicable(String ruleId) {
        return applicableRuleIds.contains(ruleId);
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
