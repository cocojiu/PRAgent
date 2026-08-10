package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.entity.ReviewPolicyPromotionEvidence;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewPolicyPromotionEvidenceMapper;
import com.repoguard.agent.mapper.projection.ReviewPolicyPromotionEvidenceProjection;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.quality.ReviewQualityGatePolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewPolicyPromotionEvidenceStoreTest {

    private static final LocalDateTime CAPTURED_AT = LocalDateTime.of(2026, 8, 10, 16, 30);
    private static final LocalDateTime SAMPLE_CUTOFF = LocalDateTime.of(2026, 8, 10, 16, 15);
    private static final String FINGERPRINT = "a".repeat(64);

    private final ReviewPolicyPromotionEvidenceMapper mapper =
        org.mockito.Mockito.mock(ReviewPolicyPromotionEvidenceMapper.class);
    private final ReviewPolicyAuditContextProvider auditContextProvider =
        org.mockito.Mockito.mock(ReviewPolicyAuditContextProvider.class);
    private final ReviewQualityGatePolicy qualityGatePolicy = new ReviewQualityGatePolicy();
    private final ReviewPolicyPromotionEvidenceStore store = new ReviewPolicyPromotionEvidenceStore(
        mapper,
        auditContextProvider,
        qualityGatePolicy
    );

    @Test
    void rulePromotionPersistsCompleteAuditEvidence() {
        ReviewRuleQualityGateDto gate = gate(30, 30);
        ReviewCalibrationQueueDto evaluation = new ReviewCalibrationQueueDto(
            version(),
            30,
            40,
            30,
            29,
            1,
            10,
            0,
            gate,
            List.of()
        );
        when(mapper.selectRuleEvidence(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            2,
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "high-risk-verifier-v1",
            "server-risk-v2"
        )).thenReturn(window(40, 30, 40, 30));
        when(auditContextProvider.current()).thenReturn(new ReviewPolicyAuditContextProvider.AuditContext(
            7L,
            "quality-admin",
            "trace-r3",
            CAPTURED_AT
        ));
        ReviewRulePolicySnapshot snapshot = new ReviewRulePolicySnapshot();
        snapshot.setId(101L);
        snapshot.setRuleId("RG-JAVA-001");

        store.recordRulePromotion(snapshot, EnforcementMode.COMMENT, EnforcementMode.BLOCK, evaluation);

        ArgumentCaptor<ReviewPolicyPromotionEvidence> captor =
            ArgumentCaptor.forClass(ReviewPolicyPromotionEvidence.class);
        verify(mapper).insert(captor.capture());
        ReviewPolicyPromotionEvidence evidence = captor.getValue();
        assertThat(evidence.getTargetType()).isEqualTo("RULE");
        assertThat(evidence.getRulePolicySnapshotId()).isEqualTo(101L);
        assertThat(evidence.getRuleId()).isEqualTo("RG-JAVA-001");
        assertThat(evidence.getQualityBaselineVersion())
            .isEqualTo(ReviewPolicyPromotionEvidenceStore.RULE_BASELINE_VERSION);
        assertThat(evidence.getQualityGateVersion())
            .isEqualTo(ReviewPolicyPromotionEvidenceStore.QUALITY_GATE_VERSION);
        assertThat(evidence.getBaselineCalculatedAt()).isEqualTo(CAPTURED_AT);
        assertThat(evidence.getSampleCutoffAt()).isEqualTo(SAMPLE_CUTOFF);
        assertThat(evidence.getTotalSamples()).isEqualTo(40);
        assertThat(evidence.getLabeledSamples()).isEqualTo(30);
        assertThat(evidence.getConfirmedValidSamples()).isEqualTo(29);
        assertThat(evidence.getFalsePositiveSamples()).isEqualTo(1);
        assertThat(evidence.getPrecisionWilsonLowerBound())
            .isEqualByComparingTo(qualityGatePolicy.precisionLowerBound(29, 30));
        assertThat(evidence.getSampleFingerprint())
            .isEqualTo(ReviewPolicyPromotionEvidenceStore.SAMPLE_FINGERPRINT_PREFIX + FINGERPRINT);
        assertThat(evidence.getActorUserId()).isEqualTo(7L);
        assertThat(evidence.getActorUsername()).isEqualTo("quality-admin");
        assertThat(evidence.getTraceId()).isEqualTo("trace-r3");
    }

    @Test
    void strategyPromotionUsesTheCapturedReleaseAndHighRiskWindow() {
        ReviewRuleQualityGateDto gate = gate(35, 30);
        when(mapper.selectStrategyEvidence(
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "high-risk-verifier-v1",
            "server-risk-v2"
        )).thenReturn(window(50, 35, 40, 30));
        when(auditContextProvider.current()).thenReturn(new ReviewPolicyAuditContextProvider.AuditContext(
            null,
            null,
            "trace-strategy",
            CAPTURED_AT
        ));
        ReviewStrategyPolicySnapshot snapshot = new ReviewStrategyPolicySnapshot();
        snapshot.setId(202L);
        ReviewStrategyRelease release = new ReviewStrategyRelease(
            12,
            1,
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "high-risk-verifier-v1",
            "server-risk-v2",
            EnforcementMode.COMMENT,
            true
        );

        store.recordStrategyPromotion(
            snapshot,
            release,
            EnforcementMode.COMMENT,
            EnforcementMode.BLOCK,
            gate
        );

        ArgumentCaptor<ReviewPolicyPromotionEvidence> captor =
            ArgumentCaptor.forClass(ReviewPolicyPromotionEvidence.class);
        verify(mapper).insert(captor.capture());
        ReviewPolicyPromotionEvidence evidence = captor.getValue();
        assertThat(evidence.getTargetType()).isEqualTo("STRATEGY");
        assertThat(evidence.getStrategyPolicySnapshotId()).isEqualTo(202L);
        assertThat(evidence.getRulePolicySnapshotId()).isNull();
        assertThat(evidence.getTotalSamples()).isEqualTo(50);
        assertThat(evidence.getLabeledSamples()).isEqualTo(35);
        assertThat(evidence.getTotalHighRiskSamples()).isEqualTo(40);
        assertThat(evidence.getLabeledHighRiskSamples()).isEqualTo(30);
        assertThat(evidence.getQualityBaselineVersion())
            .isEqualTo(ReviewPolicyPromotionEvidenceStore.STRATEGY_BASELINE_VERSION);
    }

    @Test
    void changedSampleCountsAbortThePromotionEvidenceWrite() {
        ReviewRuleQualityGateDto gate = gate(30, 30);
        ReviewCalibrationQueueDto evaluation = new ReviewCalibrationQueueDto(
            version(),
            30,
            40,
            30,
            29,
            1,
            10,
            0,
            gate,
            List.of()
        );
        when(mapper.selectRuleEvidence(any(), any(), any(Long.class), any(), any(), any(), any(), any()))
            .thenReturn(window(40, 29, 40, 29));
        ReviewRulePolicySnapshot snapshot = new ReviewRulePolicySnapshot();
        snapshot.setId(101L);
        snapshot.setRuleId("RG-JAVA-001");

        assertThatThrownBy(() -> store.recordRulePromotion(
            snapshot,
            EnforcementMode.COMMENT,
            EnforcementMode.BLOCK,
            evaluation
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("changed during evaluation");

        verify(mapper, never()).insert(any(ReviewPolicyPromotionEvidence.class));
    }

    private ReviewCalibrationVersionDto version() {
        return new ReviewCalibrationVersionDto(
            "RG-JAVA-001",
            "Java rule",
            "rg-java-001-detector-v2",
            2,
            5,
            12,
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
        );
    }

    private ReviewRuleQualityGateDto gate(long labeled, long labeledHighRisk) {
        return new ReviewRuleQualityGateDto(
            labeled,
            labeledHighRisk,
            BigDecimal.valueOf(96.67),
            BigDecimal.valueOf(3.33),
            BigDecimal.valueOf(97.50),
            BigDecimal.valueOf(2.50),
            true,
            true,
            "PASS",
            List.of()
        );
    }

    private ReviewPolicyPromotionEvidenceProjection window(
        long total,
        long labeled,
        long totalHighRisk,
        long labeledHighRisk
    ) {
        return new ReviewPolicyPromotionEvidenceProjection(
            total,
            labeled,
            totalHighRisk,
            labeledHighRisk,
            29L,
            1L,
            39L,
            1L,
            SAMPLE_CUTOFF,
            FINGERPRINT
        );
    }
}
