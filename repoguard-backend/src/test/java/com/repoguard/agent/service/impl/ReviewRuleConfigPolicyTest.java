package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewRuleConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRuleConfigPolicyTest {

    private final ReviewRuleConfigPolicy policy = new ReviewRuleConfigPolicy();

    @Test
    void normalizesRuleIdSeverityAndStatus() {
        assertThat(policy.normalizeRuleId(" rg-java-001 ")).isEqualTo("RG-JAVA-001");
        assertThat(policy.normalizeSeverity(" high ")).isEqualTo("HIGH");
        assertThat(policy.normalizeStatus(" enabled ")).isEqualTo("ENABLED");
    }

    @Test
    void defaultsBlankSortOrderToFirstBucket() {
        assertThat(policy.nextSortOrder(List.of())).isEqualTo(10);
        assertThat(policy.nextSortOrder(null)).isEqualTo(10);
    }

    @Test
    void advancesSortOrderByTenAfterLatestRule() {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setSortOrder(30);

        assertThat(policy.nextSortOrder(List.of(rule))).isEqualTo(40);
    }

    @Test
    void defaultsNullSeverityAndStatusToExistingContractValues() {
        assertThat(policy.normalizeSeverity(null)).isEqualTo("INFO");
        assertThat(policy.normalizeStatus(null)).isEqualTo("DISABLED");
    }
}
