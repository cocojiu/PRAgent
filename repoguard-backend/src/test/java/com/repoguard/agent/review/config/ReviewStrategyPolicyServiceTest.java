package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewStrategyPolicyDto;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewStrategyPolicySnapshotMapper;
import com.repoguard.agent.review.LlmReviewVersions;
import com.repoguard.agent.review.ServerRiskAggregator;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import com.repoguard.agent.review.config.ReviewPolicyPromotionEvidenceStore.CapturedPromotionEvidence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewStrategyPolicyServiceTest {

    private final ReviewStrategyPolicySnapshotMapper mapper =
        org.mockito.Mockito.mock(ReviewStrategyPolicySnapshotMapper.class);
    private final ReviewQualityBaselineService baselineService =
        org.mockito.Mockito.mock(ReviewQualityBaselineService.class);
    private final ReviewPolicyPromotionEvidenceStore promotionEvidenceStore =
        org.mockito.Mockito.mock(ReviewPolicyPromotionEvidenceStore.class);
    private final CapturedPromotionEvidence capturedEvidence =
        org.mockito.Mockito.mock(CapturedPromotionEvidence.class);
    private final ReviewStrategyPolicyService service = new ReviewStrategyPolicyService(
        mapper,
        baselineService,
        new ReviewStrategyLifecycleGate(),
        promotionEvidenceStore
    );

    @BeforeEach
    void setUpPromotionReadModel() {
        when(baselineService.loadFreshBaseline()).thenAnswer(invocation -> baselineService.loadBaseline());
        when(promotionEvidenceStore.captureStrategyPromotion(any(), any(), any(), any()))
            .thenReturn(capturedEvidence);
    }

    @Test
    void observeCannotPromoteToCommentWithoutAnExplicitLabel() {
        when(mapper.selectOne(any())).thenReturn(snapshot(11, "OBSERVE"));
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of()));

        assertThatThrownBy(() -> service.promote("comment", 11))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("explicit labeled sample");

        verify(mapper, never()).insert(any(ReviewStrategyPolicySnapshot.class));
    }

    @Test
    void promotionCreatesANewSnapshotInsteadOfMutatingTheRelease() {
        ReviewStrategyPolicySnapshot active = snapshot(11, "OBSERVE");
        when(mapper.selectOne(any())).thenReturn(active);
        when(mapper.update(any(), any())).thenReturn(1);
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of(group(
            "MEDIUM",
            1,
            1,
            1,
            0,
            1,
            0
        ))));
        when(mapper.insert(any(ReviewStrategyPolicySnapshot.class))).thenAnswer(invocation -> {
            ReviewStrategyPolicySnapshot inserted = invocation.getArgument(0);
            inserted.setId(12L);
            return 1;
        });

        var result = service.promote("comment", 11);

        ArgumentCaptor<ReviewStrategyPolicySnapshot> captor =
            ArgumentCaptor.forClass(ReviewStrategyPolicySnapshot.class);
        verify(mapper).insert(captor.capture());
        ReviewStrategyPolicySnapshot inserted = captor.getValue();
        assertThat(inserted).isNotSameAs(active);
        assertThat(inserted.getEnforcementMode()).isEqualTo("COMMENT");
        assertThat(inserted.getChangeType()).isEqualTo("PROMOTION");
        assertThat(inserted.getSourceSnapshotId()).isEqualTo(11L);
        assertThat(inserted.getPromptVersion()).isEqualTo(active.getPromptVersion());
        assertThat(result.snapshotId()).isEqualTo(12);
        assertThat(result.enforcementMode()).isEqualTo("comment");
        verify(promotionEvidenceStore).recordStrategyPromotion(
            any(ReviewStrategyPolicySnapshot.class),
            any(CapturedPromotionEvidence.class)
        );
    }

    @Test
    void commentPromotesToBlockOnlyAfterTheQualityGateHasSufficientConfidence() {
        when(mapper.selectOne(any())).thenReturn(snapshot(12, "COMMENT"));
        when(mapper.update(any(), any())).thenReturn(1);
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of(group(
            "HIGH",
            200,
            200,
            190,
            10,
            196,
            4
        ))));
        when(mapper.insert(any(ReviewStrategyPolicySnapshot.class))).thenAnswer(invocation -> {
            ((ReviewStrategyPolicySnapshot) invocation.getArgument(0)).setId(13L);
            return 1;
        });

        var result = service.promote("block", 12);

        assertThat(result.enforcementMode()).isEqualTo("block");
        assertThat(result.qualityGate().blockEligible()).isTrue();
        assertThat(result.qualityGate().status()).isEqualTo("PASS");
        verify(promotionEvidenceStore).recordStrategyPromotion(
            any(ReviewStrategyPolicySnapshot.class),
            any(CapturedPromotionEvidence.class)
        );
    }

    @Test
    void strategyDemotionDoesNotCreatePromotionEvidence() {
        when(mapper.selectOne(any())).thenReturn(snapshot(13, "BLOCK"));
        when(mapper.update(any(), any())).thenReturn(1);
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of()));
        when(mapper.insert(any(ReviewStrategyPolicySnapshot.class))).thenAnswer(invocation -> {
            ((ReviewStrategyPolicySnapshot) invocation.getArgument(0)).setId(14L);
            return 1;
        });

        var result = service.promote("comment", 13);

        assertThat(result.enforcementMode()).isEqualTo("comment");
        verify(promotionEvidenceStore, never()).recordStrategyPromotion(
            any(ReviewStrategyPolicySnapshot.class),
            any(CapturedPromotionEvidence.class)
        );
    }

    @Test
    void rollbackCreatesANewActiveSnapshotAndRejectsUnsupportedRuntimeVersions() {
        ReviewStrategyPolicySnapshot target = snapshot(7, "COMMENT");
        when(mapper.selectById(7L)).thenReturn(target);
        when(mapper.selectOne(any())).thenReturn(snapshot(19, "OBSERVE"));
        when(mapper.update(any(), any())).thenReturn(1);
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of()));
        when(mapper.insert(any(ReviewStrategyPolicySnapshot.class))).thenAnswer(invocation -> {
            ((ReviewStrategyPolicySnapshot) invocation.getArgument(0)).setId(20L);
            return 1;
        });

        var result = service.rollback(7, 19);

        ArgumentCaptor<ReviewStrategyPolicySnapshot> captor =
            ArgumentCaptor.forClass(ReviewStrategyPolicySnapshot.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("ROLLBACK");
        assertThat(captor.getValue().getSourceSnapshotId()).isEqualTo(7L);
        assertThat(result.snapshotId()).isEqualTo(20);

        ReviewStrategyPolicySnapshot unsupported = snapshot(8, "OBSERVE");
        unsupported.setPromptVersion("review-prompt-v1");
        when(mapper.selectById(8L)).thenReturn(unsupported);

        assertThatThrownBy(() -> service.rollback(8, 19))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("unsupported");
    }

    @Test
    void staleStrategySnapshotIsRejectedBeforePromotion() {
        when(mapper.selectOne(any())).thenReturn(snapshot(11, "OBSERVE"));

        assertThatThrownBy(() -> service.promote("comment", 10))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT));

        verify(mapper, never()).insert(any(ReviewStrategyPolicySnapshot.class));
    }

    @Test
    void failedConditionalStrategyActivationReturnsConflict() {
        when(mapper.selectOne(any())).thenReturn(snapshot(11, "OBSERVE"));
        when(mapper.update(any(), any())).thenReturn(0);
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of(group(
            "MEDIUM",
            1,
            1,
            1,
            0,
            1,
            0
        ))));

        assertThatThrownBy(() -> service.promote("comment", 11))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT));

        verify(mapper, never()).insert(any(ReviewStrategyPolicySnapshot.class));
    }

    @Test
    void strategyHistoryUsesCursorPagination() {
        when(baselineService.loadBaseline()).thenReturn(baseline(List.of()));
        when(mapper.selectList(any())).thenReturn(List.of(
            snapshot(13, "COMMENT"),
            snapshot(12, "OBSERVE"),
            snapshot(11, "OBSERVE")
        ));
        when(mapper.selectCount(any())).thenReturn(4L);

        var page = service.list(null, 2);

        assertThat(page.items()).extracting(ReviewStrategyPolicyDto::snapshotId).containsExactly(13L, 12L);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("12");
    }

    private ReviewStrategyPolicySnapshot snapshot(long id, String mode) {
        ReviewStrategyPolicySnapshot snapshot = new ReviewStrategyPolicySnapshot();
        snapshot.setId(id);
        snapshot.setStrategyVersion(1L);
        snapshot.setPromptVersion(LlmReviewVersions.PROMPT);
        snapshot.setContextVersion(LlmReviewVersions.CONTEXT);
        snapshot.setSchemaVersion(LlmReviewVersions.SCHEMA);
        snapshot.setVerifierVersion(LlmReviewVersions.VERIFIER);
        snapshot.setAggregationVersion(ServerRiskAggregator.VERSION);
        snapshot.setEnforcementMode(mode);
        snapshot.setReplayVerified(true);
        snapshot.setActive(true);
        snapshot.setChangeType("BASELINE");
        snapshot.setCreatedAt(LocalDateTime.of(2026, 7, 30, 12, 0));
        return snapshot;
    }

    private ReviewQualityBaseline baseline(List<ReviewQualityGroupBaseline> groups) {
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
            groups
        );
    }

    private ReviewQualityGroupBaseline group(
        String severity,
        long total,
        long labeled,
        long confirmed,
        long falsePositives,
        long anchored,
        long duplicates
    ) {
        return new ReviewQualityGroupBaseline(
            "UNASSIGNED",
            "LLM",
            "octocat/demo",
            "JAVA",
            severity,
            "strategy-v1",
            "llm-review-v2",
            1,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            total,
            labeled,
            BigDecimal.ZERO,
            confirmed,
            falsePositives,
            total - labeled,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "HIGH".equals(severity) ? total : 0,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            anchored,
            BigDecimal.ZERO,
            duplicates,
            BigDecimal.ZERO,
            labeled < 30 ? "INSUFFICIENT_SAMPLE" : "PASS",
            List.of()
        );
    }
}
