package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewRuleApplicabilityTest {

    @Test
    void defaultsToApplicableWhenRuleIsNotConfigured() {
        assertThat(ReviewRuleApplicability.isApplicable("RG-JAVA-002", "src/App.java", Map.of()))
            .isTrue();
    }

    @Test
    void disabledRuleIsNotApplicable() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            "RG-JAVA-002",
            new ReviewRuleSettings("RG-JAVA-002", "DISABLED", "")
        );

        assertThat(ReviewRuleApplicability.isApplicable("RG-JAVA-002", "src/App.java", rules))
            .isFalse();
    }

    @Test
    void matchesCommaAndNewlineSeparatedGlobPatterns() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            "RG-JAVA-002",
            new ReviewRuleSettings("RG-JAVA-002", "ENABLED", "docs/*.md\nsrc/main/java/*.java")
        );

        assertThat(ReviewRuleApplicability.isApplicable("RG-JAVA-002", "src/main/java/App.java", rules))
            .isTrue();
        assertThat(ReviewRuleApplicability.isApplicable("RG-JAVA-002", "src/test/java/AppTest.java", rules))
            .isFalse();
    }

    @Test
    void treatsRegexMetacharactersInGlobLiteralSegmentsAsLiterals() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            "RG-JAVA-002",
            new ReviewRuleSettings("RG-JAVA-002", "ENABLED", "src/(api)/[v1]/*.java")
        );

        assertThat(ReviewRuleApplicability.isApplicable(
            "RG-JAVA-002",
            "src/(api)/[v1]/UserController.java",
            rules
        )).isTrue();
        assertThat(ReviewRuleApplicability.isApplicable(
            "RG-JAVA-002",
            "src/api/v1/UserController.java",
            rules
        )).isFalse();
    }
}
