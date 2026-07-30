package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewRuleProviderTest {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewRuleRegistry reviewRuleRegistry = org.mockito.Mockito.mock(ReviewRuleRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ReviewRuleProvider provider = new ReviewRuleProvider(
        reviewRuleConfigMapper,
        reviewRuleRegistry,
        meterRegistry
    );

    @Test
    void getRulesByIdReturnsStableRuleSettings() {
        when(reviewRuleRegistry.contains("RG-JAVA-001")).thenReturn(true);
        when(reviewRuleRegistry.detectorVersion("RG-JAVA-001")).thenReturn("rg-java-001-detector-v2");
        when(reviewRuleRegistry.ruleIds()).thenReturn(Set.of("RG-JAVA-001"));
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(
            rule("RG-JAVA-001", "ENABLED", "*.java", "HIGH", 87, "BLOCK"),
            rule("RG-JAVA-001", "DISABLED", "*.kt", "LOW", 20, "OBSERVE"),
            rule(null, "ENABLED", "*")
        ));

        assertThatThrownBy(provider::getRulesById)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate review rule configuration");
    }

    @Test
    void getRulesByIdReturnsImmutableCompleteSnapshot() {
        when(reviewRuleRegistry.contains("RG-JAVA-001")).thenReturn(true);
        when(reviewRuleRegistry.detectorVersion("RG-JAVA-001")).thenReturn("rg-java-001-detector-v2");
        when(reviewRuleRegistry.ruleIds()).thenReturn(Set.of("RG-JAVA-001"));
        ReviewRuleConfig configured = rule("RG-JAVA-001", "ENABLED", "*.java", "HIGH", 87, "BLOCK");
        configured.setPositiveExample("catch (IOException ex)");
        configured.setFalsePositiveGuidance("Generated adapters are excluded");
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(configured));

        Map<String, ReviewRuleSettings> rulesById = provider.getRulesById();

        ReviewRuleSettings settings = rulesById.get("RG-JAVA-001");
        assertThat(settings.severity()).isEqualTo("HIGH");
        assertThat(settings.confidence()).isEqualTo(87);
        assertThat(settings.enforcementMode()).isEqualTo(EnforcementMode.BLOCK);
        assertThat(settings.positiveExample()).isEqualTo("catch (IOException ex)");
        assertThat(settings.falsePositiveGuidance()).isEqualTo("Generated adapters are excluded");
        assertThat(settings.detectorVersion()).isEqualTo("rg-java-001-detector-v2");
        assertThatThrownBy(() -> rulesById.put("RG-JAVA-002", settings))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enabledConfigurationWithoutDetectorFailsAndRecordsMetric() {
        when(reviewRuleRegistry.contains("RG-UNKNOWN-001")).thenReturn(false);
        when(reviewRuleRegistry.ruleIds()).thenReturn(Set.of("RG-JAVA-001"));
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(
            rule("RG-UNKNOWN-001", "ENABLED", "*.java", "HIGH", 90, "BLOCK")
        ));

        assertThatThrownBy(provider::getRulesById)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no registered detector implementation");
        assertThat(meterRegistry.counter(
            "repoguard.review.rule.configuration_error",
            "reason",
            "detector_missing",
            "rule_id",
            "RG-UNKNOWN-001"
        ).count()).isEqualTo(1);
    }

    @Test
    void getRulesByIdReturnsEmptyMapWhenRulesAreMissing() {
        when(reviewRuleRegistry.ruleIds()).thenReturn(Set.of("RG-JAVA-001"));
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(null);

        Map<String, ReviewRuleSettings> rulesById = provider.getRulesById();

        assertThat(rulesById).isEmpty();
        assertThat(meterRegistry.counter(
            "repoguard.review.rule.configuration_missing",
            "rule_id",
            "RG-JAVA-001"
        ).count()).isEqualTo(1);
    }

    private ReviewRuleConfig rule(String id, String status, String filePatterns) {
        return rule(id, status, filePatterns, "MEDIUM", 90, "COMMENT");
    }

    private ReviewRuleConfig rule(
        String id,
        String status,
        String filePatterns,
        String severity,
        int confidence,
        String enforcementMode
    ) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(id);
        rule.setStatus(status);
        rule.setFilePatterns(filePatterns);
        rule.setSeverity(severity);
        rule.setConfidence(confidence);
        rule.setEnforcementMode(enforcementMode);
        return rule;
    }
}
