package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.ReviewRulePolicySnapshotMapper;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.service.ReviewCalibrationService;
import com.repoguard.agent.review.config.ReviewPolicyPromotionEvidenceStore.CapturedPromotionEvidence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewRulePolicyGovernanceServiceTest {

    private static final String RULE_ID = "RG-JAVA-001";
    private static final String DETECTOR_VERSION = "rg-java-001-detector-v2";

    private final ReviewRuleConfigMapper ruleMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
    private final ReviewQualityBaselineService baselineService =
        org.mockito.Mockito.mock(ReviewQualityBaselineService.class);
    private final ReviewRuleRegistry registry = org.mockito.Mockito.mock(ReviewRuleRegistry.class);
    private final ReviewRulePolicySnapshotMapper snapshotMapper =
        org.mockito.Mockito.mock(ReviewRulePolicySnapshotMapper.class);
    private final ReviewRulePolicySnapshotStore snapshotStore = new ReviewRulePolicySnapshotStore(snapshotMapper);
    private final ReviewStrategyPolicyService strategyPolicyService =
        org.mockito.Mockito.mock(ReviewStrategyPolicyService.class);
    private final ReviewCalibrationService calibrationService =
        org.mockito.Mockito.mock(ReviewCalibrationService.class);
    private final ReviewPolicyPromotionEvidenceStore promotionEvidenceStore =
        org.mockito.Mockito.mock(ReviewPolicyPromotionEvidenceStore.class);
    private final CapturedPromotionEvidence capturedEvidence =
        org.mockito.Mockito.mock(CapturedPromotionEvidence.class);
    private final ReviewRuleConfigServiceImpl service = new ReviewRuleConfigServiceImpl(
        ruleMapper,
        findingMapper,
        cacheEvictionService,
        new ReviewRuleConfigPolicy(),
        new ReviewRuleMetricAssembler(),
        baselineService,
        registry,
        snapshotStore,
        new ReviewRuleLifecycleGate(),
        strategyPolicyService,
        calibrationService,
        promotionEvidenceStore
    );

    @BeforeEach
    void setUpPromotionEvidenceCapture() {
        when(promotionEvidenceStore.captureRulePromotion(any(), any(), any()))
            .thenReturn(capturedEvidence);
    }

    @Test
    void semanticEditIncrementsBothVersionsAndForcesObserve() {
        ReviewRuleConfig rule = rule();
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(registry.detectorVersion(RULE_ID)).thenReturn(DETECTOR_VERSION);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(ruleMapper.update(any(ReviewRuleConfig.class), any())).thenReturn(1);
        when(findingMapper.selectReviewRuleHitCounts()).thenReturn(List.of());
        when(baselineService.loadBaseline()).thenReturn(emptyBaseline());

        var result = service.updateReviewRule(RULE_ID, request("Changed semantic description", "BLOCK"), 5);

        assertThat(rule.getConfigVersion()).isEqualTo(3);
        assertThat(rule.getPolicyVersion()).isEqualTo(6);
        assertThat(rule.getEnforcementMode()).isEqualTo("OBSERVE");
        assertThat(result.enforcementMode()).isEqualTo("observe");
        ArgumentCaptor<ReviewRulePolicySnapshot> captor = ArgumentCaptor.forClass(ReviewRulePolicySnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("CONFIG_UPDATE_OBSERVE");
        assertThat(captor.getValue().getConfigVersion()).isEqualTo(3);
        assertThat(captor.getValue().getPolicyVersion()).isEqualTo(6);
        assertThat(captor.getValue().getSourcePolicyVersion()).isEqualTo(5);
    }

    @Test
    void observeCannotPromoteToCommentWithoutExplicitSamples() {
        ReviewRuleConfig rule = rule();
        rule.setEnforcementMode("OBSERVE");
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(registry.detectorVersion(RULE_ID)).thenReturn(DETECTOR_VERSION);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(calibrationService.getQueue(RULE_ID, 1, false)).thenReturn(queueWithGate(
            new ReviewRuleQualityGateDto(
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                "INSUFFICIENT_SAMPLE",
                List.of("labeled_high_risk_samples_below_30")
            )
        ));

        assertThatThrownBy(() -> service.updateReviewRule(RULE_ID, request(rule.getDescription(), "COMMENT"), 5))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("explicit labeled sample");

        verify(ruleMapper, never()).updateById(any(ReviewRuleConfig.class));
        verify(snapshotMapper, never()).insert(any(ReviewRulePolicySnapshot.class));
        verify(calibrationService).getQueue(RULE_ID, 1, false);
    }

    @Test
    void successfulBlockPromotionRecordsTheCapturedQualityEvidence() {
        ReviewRuleConfig rule = rule();
        ReviewRuleQualityGateDto gate = new ReviewRuleQualityGateDto(
            30,
            30,
            BigDecimal.valueOf(96.67),
            BigDecimal.valueOf(3.33),
            BigDecimal.valueOf(96.67),
            BigDecimal.valueOf(3.33),
            true,
            true,
            "PASS",
            List.of()
        );
        ReviewCalibrationQueueDto evaluation = queueWithGate(
            new ReviewCalibrationVersionDto(
                RULE_ID,
                "Current rule",
                DETECTOR_VERSION,
                2,
                5,
                10,
                1,
                "review-prompt-v2",
                "review-context-v2",
                "review-schema-v2",
                "high-risk-verifier-v1",
                "server-risk-v2",
                "comment",
                "comment",
                true,
                "captured-version"
            ),
            gate
        );
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(registry.detectorVersion(RULE_ID)).thenReturn(DETECTOR_VERSION);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(ruleMapper.update(any(ReviewRuleConfig.class), any())).thenReturn(1);
        when(calibrationService.getQueue(RULE_ID, 1, false)).thenReturn(evaluation);
        when(findingMapper.selectReviewRuleHitCounts()).thenReturn(List.of());
        when(baselineService.loadBaseline()).thenReturn(emptyBaseline());

        service.updateReviewRule(RULE_ID, request(rule.getDescription(), "BLOCK"), 5);

        verify(promotionEvidenceStore).recordRulePromotion(
            any(ReviewRulePolicySnapshot.class),
            any(CapturedPromotionEvidence.class)
        );
    }

    @Test
    void rollbackRestoresConfigurationIntoANewPolicyVersion() {
        ReviewRuleConfig active = rule();
        ReviewRulePolicySnapshot historic = historicSnapshot();
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(registry.detectorVersion(RULE_ID)).thenReturn(DETECTOR_VERSION);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(active);
        when(snapshotMapper.selectOne(any())).thenReturn(historic);
        when(ruleMapper.update(any(ReviewRuleConfig.class), any())).thenReturn(1);
        when(findingMapper.selectReviewRuleHitCounts()).thenReturn(List.of());
        when(baselineService.loadBaseline()).thenReturn(emptyBaseline());

        var result = service.rollbackReviewRule(RULE_ID, 2, 5);

        assertThat(active.getConfigVersion()).isEqualTo(1);
        assertThat(active.getPolicyVersion()).isEqualTo(6);
        assertThat(active.getRuleName()).isEqualTo("Historic rule");
        assertThat(result.policyVersion()).isEqualTo(6);
        ArgumentCaptor<ReviewRulePolicySnapshot> captor = ArgumentCaptor.forClass(ReviewRulePolicySnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("ROLLBACK");
        assertThat(captor.getValue().getSourcePolicyVersion()).isEqualTo(2);
        assertThat(captor.getValue().getPolicyVersion()).isEqualTo(6);
        verify(ruleMapper).update(any(ReviewRuleConfig.class), any());
    }

    @Test
    void stalePolicyVersionIsRejectedBeforeRuleMutation() {
        ReviewRuleConfig rule = rule();
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);

        assertThatThrownBy(() -> service.updateReviewRule(
            RULE_ID,
            request("Changed semantic description", "COMMENT"),
            4
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT));

        verify(ruleMapper, never()).update(any(ReviewRuleConfig.class), any());
        verify(snapshotMapper, never()).insert(any(ReviewRulePolicySnapshot.class));
    }

    @Test
    void concurrentRuleUpdateThatLosesConditionalWriteReturnsConflict() {
        ReviewRuleConfig rule = rule();
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(registry.detectorVersion(RULE_ID)).thenReturn(DETECTOR_VERSION);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(ruleMapper.update(any(ReviewRuleConfig.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.updateReviewRule(
            RULE_ID,
            request("Changed semantic description", "COMMENT"),
            5
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT));

        verify(snapshotMapper, never()).insert(any(ReviewRulePolicySnapshot.class));
    }

    @Test
    void rulePolicyHistoryUsesCursorPagination() {
        ReviewRuleConfig active = rule();
        ReviewRulePolicySnapshot versionFive = historicSnapshot();
        versionFive.setPolicyVersion(5L);
        ReviewRulePolicySnapshot versionFour = historicSnapshot();
        versionFour.setPolicyVersion(4L);
        ReviewRulePolicySnapshot versionThree = historicSnapshot();
        versionThree.setPolicyVersion(3L);
        when(registry.contains(RULE_ID)).thenReturn(true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(active);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(versionFive, versionFour, versionThree));
        when(snapshotMapper.selectCount(any())).thenReturn(5L);

        var page = service.getReviewRuleVersions(RULE_ID, null, 2);

        assertThat(page.items()).extracting(ReviewRulePolicyVersionDto::policyVersion).containsExactly(5L, 4L);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("4");
    }

    private ReviewRuleConfigRequest request(String description, String mode) {
        return new ReviewRuleConfigRequest(
            RULE_ID,
            "Current rule",
            "Java Patch",
            "Java",
            "*.java",
            "HIGH",
            "ENABLED",
            92,
            description,
            "positive",
            "false positive guidance",
            mode
        );
    }

    private ReviewRuleConfig rule() {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(RULE_ID);
        rule.setDetectorVersion(DETECTOR_VERSION);
        rule.setConfigVersion(2L);
        rule.setPolicyVersion(5L);
        rule.setRuleName("Current rule");
        rule.setScope("Java Patch");
        rule.setApplicableLanguages("Java");
        rule.setFilePatterns("*.java");
        rule.setSeverity("HIGH");
        rule.setStatus("ENABLED");
        rule.setConfidence(92);
        rule.setEnforcementMode("COMMENT");
        rule.setDescription("Current description");
        rule.setPositiveExample("positive");
        rule.setFalsePositiveGuidance("false positive guidance");
        rule.setSortOrder(10);
        rule.setCreatedAt(LocalDateTime.of(2026, 7, 1, 12, 0));
        rule.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 12, 0));
        return rule;
    }

    private ReviewRulePolicySnapshot historicSnapshot() {
        ReviewRulePolicySnapshot snapshot = new ReviewRulePolicySnapshot();
        snapshot.setRuleId(RULE_ID);
        snapshot.setPolicyVersion(2L);
        snapshot.setConfigVersion(1L);
        snapshot.setDetectorVersion(DETECTOR_VERSION);
        snapshot.setRuleName("Historic rule");
        snapshot.setScope("Java Patch");
        snapshot.setApplicableLanguages("Java");
        snapshot.setFilePatterns("*.java");
        snapshot.setSeverity("MEDIUM");
        snapshot.setStatus("ENABLED");
        snapshot.setConfidence(88);
        snapshot.setEnforcementMode("COMMENT");
        snapshot.setDescription("Historic description");
        snapshot.setPositiveExample("historic positive");
        snapshot.setFalsePositiveGuidance("historic guidance");
        snapshot.setChangeType("BASELINE");
        snapshot.setCreatedAt(LocalDateTime.of(2026, 7, 1, 12, 0));
        return snapshot;
    }

    private ReviewQualityBaseline emptyBaseline() {
        return new ReviewQualityBaseline(
            0,
            0,
            BigDecimal.ZERO,
            0,
            0,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of()
        );
    }

    private ReviewCalibrationQueueDto queueWithGate(ReviewRuleQualityGateDto gate) {
        return queueWithGate(null, gate);
    }

    private ReviewCalibrationQueueDto queueWithGate(
        ReviewCalibrationVersionDto version,
        ReviewRuleQualityGateDto gate
    ) {
        return new ReviewCalibrationQueueDto(
            version,
            30,
            0,
            gate.labeledHighRiskSamples(),
            0,
            0,
            0,
            Math.max(0, 30 - gate.labeledHighRiskSamples()),
            gate,
            List.of()
        );
    }
}
