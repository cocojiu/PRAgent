package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.mapper.ReviewQualityBaselineMapper;
import com.repoguard.agent.mapper.ReviewQualityBaselineSnapshotMapper;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineSnapshotState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ReviewQualityBaselineSnapshotServiceTest {

    private final ReviewQualityBaselineSnapshotMapper snapshotMapper =
        org.mockito.Mockito.mock(ReviewQualityBaselineSnapshotMapper.class);
    private final ReviewQualityBaselineMapper baselineMapper =
        org.mockito.Mockito.mock(ReviewQualityBaselineMapper.class);
    private final ReviewQualityBaselineSnapshotCodec codec = new ReviewQualityBaselineSnapshotCodec(
        new ObjectMapper().findAndRegisterModules()
    );
    private final ReviewQualityBaselineService service = new ReviewQualityBaselineService(
        snapshotMapper,
        new ReviewQualityBaselineCalculator(baselineMapper, new ReviewQualityGatePolicy()),
        codec,
        new ImmediateTransactionManager()
    );

    @Test
    void returnsCleanPersistedSnapshotWithoutRecalculating() {
        ReviewQualityBaseline persisted = baseline(12);
        when(snapshotMapper.selectState()).thenReturn(new ReviewQualityBaselineSnapshotState(
            4,
            4,
            codec.encode(persisted),
            LocalDateTime.parse("2026-08-10T20:00:00")
        ));

        ReviewQualityBaseline result = service.loadBaseline();

        assertThat(result).isEqualTo(persisted);
        verify(baselineMapper, never()).selectSummary();
        verify(snapshotMapper, never()).markRefreshed(any(Long.class), anyString(), any());
    }

    @Test
    void refreshesDirtySnapshotAtTheCapturedSourceVersion() {
        when(snapshotMapper.selectState()).thenReturn(new ReviewQualityBaselineSnapshotState(
            7,
            6,
            codec.encode(baseline(2)),
            LocalDateTime.parse("2026-08-10T20:00:00")
        ));
        when(snapshotMapper.markRefreshed(eq(7L), anyString(), any())).thenReturn(1);

        ReviewQualityBaseline result = service.loadBaseline();

        assertThat(result).isEqualTo(baseline(0));
        verify(snapshotMapper).markRefreshed(eq(7L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void returnsNewerPersistedSnapshotWhenAStaleRefreshLosesTheVersionRace() {
        ReviewQualityBaseline newer = baseline(21);
        ReviewQualityBaselineSnapshotState dirty = new ReviewQualityBaselineSnapshotState(
            8,
            7,
            codec.encode(baseline(3)),
            LocalDateTime.parse("2026-08-10T20:00:00")
        );
        ReviewQualityBaselineSnapshotState refreshed = new ReviewQualityBaselineSnapshotState(
            9,
            9,
            codec.encode(newer),
            LocalDateTime.parse("2026-08-10T20:01:00")
        );
        when(snapshotMapper.selectState()).thenReturn(dirty, refreshed);
        when(snapshotMapper.markRefreshed(eq(8L), anyString(), any())).thenReturn(0);

        ReviewQualityBaseline result = service.loadBaseline();

        assertThat(result).isEqualTo(newer);
        verify(snapshotMapper).markRefreshed(eq(8L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void corruptCleanSnapshotIsMarkedDirtyAndRebuilt() {
        ReviewQualityBaselineSnapshotState corrupt = new ReviewQualityBaselineSnapshotState(
            10,
            10,
            "{broken-json",
            LocalDateTime.parse("2026-08-10T20:00:00")
        );
        ReviewQualityBaselineSnapshotState dirty = new ReviewQualityBaselineSnapshotState(
            11,
            10,
            "{broken-json",
            LocalDateTime.parse("2026-08-10T20:00:00")
        );
        when(snapshotMapper.selectState()).thenReturn(corrupt, dirty);
        when(snapshotMapper.markRefreshed(eq(11L), anyString(), any())).thenReturn(1);

        ReviewQualityBaseline result = service.loadBaseline();

        assertThat(result).isEqualTo(baseline(0));
        verify(snapshotMapper).markDirty();
        verify(snapshotMapper).markRefreshed(eq(11L), anyString(), any(LocalDateTime.class));
    }

    private ReviewQualityBaseline baseline(long totalFindings) {
        return new ReviewQualityBaseline(
            totalFindings,
            0,
            BigDecimal.ZERO.setScale(2),
            0,
            0,
            0,
            BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2),
            0,
            BigDecimal.ZERO.setScale(2),
            0,
            BigDecimal.ZERO.setScale(2),
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of()
        );
    }

    private static final class ImmediateTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
