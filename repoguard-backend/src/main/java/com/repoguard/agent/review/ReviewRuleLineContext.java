package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;

record ReviewRuleLineContext(
    String filePath,
    int lineNumber,
    String line,
    String trimmedLine,
    Map<String, ReviewRuleSettings> configuredRules,
    boolean patchHasAuthorizationGuard
) {

    ReviewRuleLineContext(
        String filePath,
        int lineNumber,
        String line,
        String trimmedLine,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        this(filePath, lineNumber, line, trimmedLine, configuredRules, false);
    }

    boolean isApplicable(String ruleId) {
        return ReviewRuleApplicability.isApplicable(ruleId, filePath, configuredRules);
    }

    String normalizePath(String value) {
        return ReviewRuleApplicability.normalizePath(value);
    }
}
