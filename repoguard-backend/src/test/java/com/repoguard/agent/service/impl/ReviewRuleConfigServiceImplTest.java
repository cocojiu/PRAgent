package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleFeedbackStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleHitCount;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewRuleConfigServiceImplTest {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
    private final ReviewRuleConfigServiceImpl service = new ReviewRuleConfigServiceImpl(
        reviewRuleConfigMapper,
        reviewFindingMapper,
        cacheEvictionService,
        new ReviewRuleConfigPolicy(),
        new ReviewRuleMetricAssembler()
    );

    @Test
    void getReviewRulesReturnsRulesAndMetricsFromDatabase() {
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(
            rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88),
            rule("RG-SECRET-001", "硬编码密钥检测", "HIGH", "DISABLED", 96)
        ));
        when(reviewFindingMapper.selectReviewRuleHitCounts()).thenReturn(List.of(
            ruleHitCount("RG-JAVA-001", 2L),
            ruleHitCount("RG-SECRET-001", 1L)
        ));
        when(reviewFindingMapper.selectReviewRuleFeedbackStat()).thenReturn(ruleFeedbackStat(3L, 1L, 1L, 2L));

        var result = service.getReviewRules();

        assertThat(result.rules()).hasSize(2);
        assertThat(result.rules().getFirst().id()).isEqualTo("RG-JAVA-001");
        assertThat(result.rules().getFirst().status()).isEqualTo("enabled");
        assertThat(result.rules().getFirst().hitCount()).isEqualTo(2);
        assertThat(result.rules().getFirst().applicableLanguages()).isEqualTo("Java");
        assertThat(result.rules().getFirst().filePatterns()).isEqualTo("*.java");
        assertThat(result.rules().getFirst().falsePositiveGuidance()).contains("false positive");
        assertThat(result.metrics()).hasSize(6);
        assertThat(result.metrics().get(4).value()).isEqualTo("50%");
        assertThat(result.metrics().get(5).value()).isEqualTo("50%");
        assertThat(result.metrics()).extracting("label").contains("启用规则", "累计命中");
    }

    @Test
    void createReviewRuleNormalizesFieldsAndEvictsRuleCaches() {
        when(reviewRuleConfigMapper.selectById("RG-JAVA-002")).thenReturn(null);
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88)));

        var result = service.createReviewRule(request("rg-java-002", "New Rule", " low ", " enabled "));

        assertThat(result.id()).isEqualTo("RG-JAVA-002");
        assertThat(result.severity()).isEqualTo("low");
        assertThat(result.status()).isEqualTo("enabled");
        assertThat(result.hitCount()).isZero();

        ArgumentCaptor<ReviewRuleConfig> ruleCaptor = ArgumentCaptor.forClass(ReviewRuleConfig.class);
        verify(reviewRuleConfigMapper).insert(ruleCaptor.capture());
        ReviewRuleConfig inserted = ruleCaptor.getValue();
        assertThat(inserted.getId()).isEqualTo("RG-JAVA-002");
        assertThat(inserted.getRuleName()).isEqualTo("New Rule");
        assertThat(inserted.getSeverity()).isEqualTo("LOW");
        assertThat(inserted.getStatus()).isEqualTo("ENABLED");
        assertThat(inserted.getSortOrder()).isEqualTo(20);
        verify(cacheEvictionService).evictReviewRules();
        verify(cacheEvictionService).evictDashboardRules();
    }

    @Test
    void updateReviewRuleStatusPersistsNormalizedStatus() {
        ReviewRuleConfig rule = rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88);
        when(reviewRuleConfigMapper.selectById("RG-JAVA-001")).thenReturn(rule);
        when(reviewFindingMapper.selectReviewRuleHitCounts()).thenReturn(List.of());

        var result = service.updateReviewRuleStatus("rg-java-001", "disabled");

        assertThat(rule.getStatus()).isEqualTo("DISABLED");
        assertThat(result.status()).isEqualTo("disabled");
        verify(reviewRuleConfigMapper).updateById(rule);
        verify(cacheEvictionService).evictReviewRules();
        verify(cacheEvictionService).evictDashboardRules();
    }

    @Test
    void createReviewRuleRejectsDuplicateRuleId() {
        when(reviewRuleConfigMapper.selectById("RG-JAVA-001"))
            .thenReturn(rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88));

        assertThatThrownBy(() -> service.createReviewRule(request("rg-java-001", "New Rule", "LOW", "ENABLED")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review rule already exists");
    }

    @Test
    void updateReviewRuleRejectsMismatchedPathAndBodyId() {
        assertThatThrownBy(() -> service.updateReviewRule("rg-java-001", request("rg-java-002", "New Rule", "LOW", "ENABLED")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review rule id in path and body must match");
    }

    @Test
    void constructorRejectsMissingRuleMetricAssembler() {
        assertThatThrownBy(() -> new ReviewRuleConfigServiceImpl(
            reviewRuleConfigMapper,
            reviewFindingMapper,
            cacheEvictionService,
            new ReviewRuleConfigPolicy(),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("reviewRuleMetricAssembler");
    }

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new ReviewRuleConfigServiceImpl(
            reviewRuleConfigMapper,
            reviewFindingMapper,
            null,
            new ReviewRuleConfigPolicy(),
            new ReviewRuleMetricAssembler()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    private ReviewRuleConfigRequest request(String id, String name, String severity, String status) {
        return new ReviewRuleConfigRequest(
            id,
            name,
            "Java Patch",
            "Java",
            "*.java",
            severity,
            status,
            92,
            "Rule description",
            "catch (IOException ex)",
            "Mark as false positive for framework boundaries."
        );
    }

    private ReviewRuleConfig rule(String id, String name, String severity, String status, int confidence) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(id);
        rule.setRuleName(name);
        rule.setScope("Java Patch");
        rule.setApplicableLanguages("Java");
        rule.setFilePatterns("*.java");
        rule.setSeverity(severity);
        rule.setStatus(status);
        rule.setConfidence(confidence);
        rule.setDescription(name + " description");
        rule.setPositiveExample("catch (IOException ex)");
        rule.setFalsePositiveGuidance("Mark as false positive for framework boundaries.");
        rule.setSortOrder(10);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        return rule;
    }

    private RuleHitCount ruleHitCount(String ruleId, Long total) {
        return new RuleHitCount(ruleId, total);
    }

    private RuleFeedbackStat ruleFeedbackStat(
        Long totalHits,
        Long validCount,
        Long falsePositiveCount,
        Long reviewedCount
    ) {
        return new RuleFeedbackStat(totalHits, validCount, falsePositiveCount, reviewedCount);
    }
}
