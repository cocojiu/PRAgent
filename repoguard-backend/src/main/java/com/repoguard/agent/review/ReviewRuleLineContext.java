package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.Map;

record ReviewRuleLineContext(
    String filePath,
    int lineNumber,
    String line,
    String trimmedLine,
    Map<String, ReviewRuleSettings> configuredRules
) {

    boolean isApplicable(String ruleId) {
        return ReviewRuleApplicability.isApplicable(ruleId, filePath, configuredRules);
    }

    String normalizePath(String value) {
        return ReviewRuleApplicability.normalizePath(value);
    }
}
