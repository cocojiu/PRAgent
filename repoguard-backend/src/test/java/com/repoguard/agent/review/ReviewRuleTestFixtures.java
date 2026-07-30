package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleProvider;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReviewRuleTestFixtures {

    private ReviewRuleTestFixtures() {
    }

    static RuleBasedPullRequestReviewer defaultReviewer(ReviewRuleProvider reviewRuleProvider) {
        RuleMatchFactory matchFactory = new RuleMatchFactory();
        return new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            defaultLineRules(matchFactory),
            defaultPullRequestRules(matchFactory)
        );
    }

    static List<ReviewRule> defaultLineRules(RuleMatchFactory matchFactory) {
        return List.of(
            new BroadExceptionCatchRule(matchFactory),
            new StandardOutputLoggingRule(matchFactory),
            new FixedSleepRule(matchFactory),
            new TodoCommentRule(matchFactory),
            new SensitiveLiteralRule(matchFactory),
            new SensitiveLoggingRule(matchFactory),
            new TaskStatusStringRule(matchFactory),
            new RabbitMessagePublishRule(matchFactory),
            new RawExternalCallRule(matchFactory),
            new DestructiveMigrationRule(matchFactory),
            new RequiredColumnWithoutDefaultRule(matchFactory),
            new GithubCommentDirectPublishRule(matchFactory),
            new ControllerAuthorizationGuardRule(matchFactory)
        );
    }

    static List<PullRequestReviewRule> defaultPullRequestRules(RuleMatchFactory matchFactory) {
        return List.of(new ControllerApiTestCoverageRule(matchFactory));
    }

    static Map<String, ReviewRuleSettings> defaultSettings() {
        return Map.ofEntries(
            setting("RG-JAVA-001", "MEDIUM", 88, EnforcementMode.COMMENT),
            setting("RG-JAVA-002", "LOW", 97, EnforcementMode.COMMENT),
            setting("RG-JAVA-003", "MEDIUM", 89, EnforcementMode.COMMENT),
            setting("RG-GEN-001", "LOW", 95, EnforcementMode.COMMENT),
            setting("RG-SECRET-001", "HIGH", 96, EnforcementMode.BLOCK),
            setting("RG-API-001", "MEDIUM", 88, EnforcementMode.COMMENT),
            setting("RG-AUTH-001", "HIGH", 92, EnforcementMode.BLOCK),
            setting("RG-STATE-001", "MEDIUM", 90, EnforcementMode.COMMENT),
            setting("RG-MQ-001", "HIGH", 91, EnforcementMode.BLOCK),
            setting("RG-EXT-001", "MEDIUM", 88, EnforcementMode.COMMENT),
            setting("RG-LOG-001", "HIGH", 94, EnforcementMode.BLOCK),
            setting("RG-DB-002", "HIGH", 94, EnforcementMode.BLOCK),
            setting("RG-DB-003", "HIGH", 90, EnforcementMode.BLOCK),
            setting("RG-GH-001", "HIGH", 93, EnforcementMode.BLOCK)
        );
    }

    static Map<String, ReviewRuleSettings> settingsFor(String ruleId) {
        ReviewRuleSettings settings = defaultSettings().get(ruleId);
        if (settings == null) {
            settings = new ReviewRuleSettings(
                ruleId,
                "ENABLED",
                "*",
                "MEDIUM",
                90,
                EnforcementMode.COMMENT,
                "",
                ""
            );
        }
        return Map.of(ruleId, settings);
    }

    static Map<String, ReviewRuleSettings> defaultSettingsWith(ReviewRuleSettings override) {
        Map<String, ReviewRuleSettings> settings = new LinkedHashMap<>(defaultSettings());
        settings.put(override.id(), override);
        return Map.copyOf(settings);
    }

    static Map<String, ReviewRuleSettings> configuredOrDefault(
        String ruleId,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        return configuredRules == null || configuredRules.isEmpty()
            ? settingsFor(ruleId)
            : configuredRules;
    }

    private static Map.Entry<String, ReviewRuleSettings> setting(
        String id,
        String severity,
        int confidence,
        EnforcementMode enforcementMode
    ) {
        return Map.entry(
            id,
            new ReviewRuleSettings(id, "ENABLED", "*", severity, confidence, enforcementMode, "", "")
        );
    }
}
