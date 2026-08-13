package com.repoguard.agent.review.quality;

import com.repoguard.agent.mapper.ReviewQualityBaselineMapper;
import com.repoguard.agent.mapper.ReviewQualityBaselineSnapshotMapper;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineSnapshotState;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provides the quality baseline through a persisted, versioned read model.
 * Expensive aggregation is performed only when the snapshot is missing or dirty.
 */
@Service
public class ReviewQualityBaselineService {

    private final ReviewQualityBaselineSnapshotMapper snapshotMapper;
    private final ReviewQualityBaselineCalculator calculator;
    private final ReviewQualityBaselineSnapshotCodec snapshotCodec;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    @Autowired
    public ReviewQualityBaselineService(
        ReviewQualityBaselineSnapshotMapper snapshotMapper,
        ReviewQualityBaselineCalculator calculator,
        ReviewQualityBaselineSnapshotCodec snapshotCodec,
        PlatformTransactionManager transactionManager
    ) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        PlatformTransactionManager manager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.readTransaction = transaction(manager, true);
        this.writeTransaction = transaction(manager, false);
    }

    /**
     * Compatibility constructor for pure unit tests that exercise the calculation itself.
     */
    public ReviewQualityBaselineService(
        ReviewQualityBaselineMapper baselineMapper,
        ReviewQualityGatePolicy qualityGatePolicy
    ) {
        this.snapshotMapper = null;
        this.calculator = new ReviewQualityBaselineCalculator(baselineMapper, qualityGatePolicy);
        this.snapshotCodec = null;
        this.readTransaction = null;
        this.writeTransaction = null;
    }

    public ReviewQualityBaseline loadBaseline() {
        if (snapshotMapper == null) {
            return calculator.calculate();
        }
        ReviewQualityBaselineSnapshotState state = snapshotMapper.selectState();
        if (state != null && !state.dirty()) {
            try {
                return snapshotCodec.decode(state.baselinePayload());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                // A corrupt or incompatible payload must become dirty so the
                // recovery worker can rebuild it instead of retrying the same
                // broken snapshot forever.
                snapshotMapper.markDirty();
                ReviewQualityBaselineSnapshotState dirtyState = snapshotMapper.selectState();
                return refreshSnapshot(
                    dirtyState == null ? state.sourceVersion() + 1 : dirtyState.sourceVersion()
                );
            }
        }
        return refreshSnapshot(state == null ? 1L : state.sourceVersion());
    }

    /**
     * Bypasses the persisted read model for safety-sensitive promotion evaluation.
     * Callers should perform this before acquiring a policy write lock.
     */
    public ReviewQualityBaseline loadFreshBaseline() {
        if (readTransaction == null) {
            return calculator.calculate();
        }
        return readTransaction.execute(status -> calculator.calculate());
    }

    /**
     * Marks the snapshot dirty in the caller's transaction. A later refresh will
     * retain the dirty version if another write races with the calculation.
     */
    public void markDirty() {
        if (snapshotMapper != null) {
            snapshotMapper.markDirty();
        }
    }

    /**
     * Rebuilds one dirty snapshot. The read and write transactions are deliberately
     * separate so a management write does not hold locks during full aggregation.
     */
    public boolean refreshIfDirty() {
        if (snapshotMapper == null) {
            return false;
        }
        ReviewQualityBaselineSnapshotState state = snapshotMapper.selectState();
        if (state == null || !state.dirty()) {
            return false;
        }
        refreshSnapshot(state.sourceVersion());
        return true;
    }

    /**
     * Performs a scheduled reconciliation even when the dirty marker was missed.
     */
    public ReviewQualityBaseline reconcile() {
        if (snapshotMapper == null) {
            return calculator.calculate();
        }
        ReviewQualityBaselineSnapshotState state = snapshotMapper.selectState();
        return refreshSnapshot(state == null ? 1L : state.sourceVersion());
    }

    private ReviewQualityBaseline refreshSnapshot(long sourceVersion) {
        ReviewQualityBaseline baseline = loadFreshBaseline();
        if (snapshotMapper == null) {
            return baseline;
        }
        String payload = snapshotCodec.encode(baseline);
        LocalDateTime calculatedAt = LocalDateTime.now();
        int updated = writeTransaction.execute(status -> snapshotMapper.markRefreshed(
            sourceVersion,
            payload,
            calculatedAt
        ));
        if (updated == 0) {
            ReviewQualityBaselineSnapshotState newer = snapshotMapper.selectState();
            if (newer != null && !newer.dirty()) {
                return snapshotCodec.decode(newer.baselinePayload());
            }
        }
        return baseline;
    }

    private TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }
}
