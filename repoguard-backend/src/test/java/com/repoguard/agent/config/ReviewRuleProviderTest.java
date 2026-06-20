package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewRuleProviderTest {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewRuleProvider provider = new ReviewRuleProvider(reviewRuleConfigMapper);

    @Test
    void getRulesByIdReturnsStableRuleSettings() {
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(
            rule("RG-JAVA-001", "ENABLED", "*.java"),
            rule("RG-JAVA-001", "DISABLED", "*.kt"),
            rule(null, "ENABLED", "*")
        ));

        Map<String, ReviewRuleSettings> rulesById = provider.getRulesById();

        assertThat(rulesById).containsOnlyKeys("RG-JAVA-001");
        ReviewRuleSettings settings = rulesById.get("RG-JAVA-001");
        assertThat(settings.status()).isEqualTo("ENABLED");
        assertThat(settings.filePatterns()).isEqualTo("*.java");
    }

    @Test
    void getRulesByIdReturnsEmptyMapWhenRulesAreMissing() {
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(null);

        Map<String, ReviewRuleSettings> rulesById = provider.getRulesById();

        assertThat(rulesById).isEmpty();
    }

    private ReviewRuleConfig rule(String id, String status, String filePatterns) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(id);
        rule.setStatus(status);
        rule.setFilePatterns(filePatterns);
        return rule;
    }
}
