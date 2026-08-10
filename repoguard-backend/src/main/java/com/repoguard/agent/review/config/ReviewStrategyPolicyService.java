package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.dto.ReviewStrategyPolicyDto;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewStrategyPolicySnapshotMapper;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewStrategyPolicyService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewStrategyPolicySnapshotMapper snapshotMapper;
    private final ReviewQualityBaselineService qualityBaselineService;
    private final ReviewStrategyLifecycleGate lifecycleGate;
    private final ReviewPolicyPromotionEvidenceStore promotionEvidenceStore;

    public ReviewStrategyPolicyService(
        ReviewStrategyPolicySnapshotMapper snapshotMapper,
        ReviewQualityBaselineService qualityBaselineService,
        ReviewStrategyLifecycleGate lifecycleGate,
        ReviewPolicyPromotionEvidenceStore promotionEvidenceStore
    ) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.qualityBaselineService = Objects.requireNonNull(qualityBaselineService, "qualityBaselineService");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
        this.promotionEvidenceStore = Objects.requireNonNull(
            promotionEvidenceStore,
            "promotionEvidenceStore"
        );
    }

    public ReviewStrategyPolicyDto getActive() {
        ReviewQualityBaseline baseline = qualityBaselineService.loadBaseline();
        return toDto(requireActive(), baseline);
    }

    ReviewStrategyPolicyDto getActive(ReviewQualityBaseline baseline) {
        return toDto(requireActive(), baseline);
    }

    public PageResponse<ReviewStrategyPolicyDto> list(Long cursor, int pageSize) {
        validatePage(cursor, pageSize);
        ReviewQualityBaseline baseline = qualityBaselineService.loadBaseline();
        LambdaQueryWrapper<ReviewStrategyPolicySnapshot> query =
            new LambdaQueryWrapper<ReviewStrategyPolicySnapshot>()
                .orderByDesc(ReviewStrategyPolicySnapshot::getId);
        if (cursor != null) {
            query.lt(ReviewStrategyPolicySnapshot::getId, cursor);
        }
        List<ReviewStrategyPolicySnapshot> snapshots = snapshotMapper.selectList(
            query.last("limit " + (pageSize + 1))
        );
        boolean hasMore = snapshots.size() > pageSize;
        List<ReviewStrategyPolicySnapshot> page = hasMore ? snapshots.subList(0, pageSize) : snapshots;
        List<ReviewStrategyPolicyDto> items = page.stream().map(snapshot -> toDto(snapshot, baseline)).toList();
        String nextCursor = hasMore ? String.valueOf(page.getLast().getId()) : null;
        long total = snapshotMapper.selectCount(new LambdaQueryWrapper<ReviewStrategyPolicySnapshot>());
        return new PageResponse<>(items, total, nextCursor, hasMore);
    }

    @Transactional
    public ReviewStrategyPolicyDto promote(String requestedMode, long expectedSnapshotId) {
        ReviewStrategyPolicySnapshot active = requireActiveForUpdate();
        requireExpectedSnapshot(active, expectedSnapshotId);
        ReviewStrategyRelease release = toRelease(active);
        EnforcementMode current = release.enforcementMode();
        EnforcementMode target = EnforcementMode.from(requestedMode);
        ReviewQualityBaseline baseline = qualityBaselineService.loadBaseline();
        if (current == target) {
            return toDto(active, baseline);
        }
        ReviewRuleQualityGateDto qualityGate = lifecycleGate.evaluate(release, baseline.groups());
        validatePromotion(release, current, target, qualityGate);
        ReviewStrategyPolicySnapshot promoted = copy(active, target, "PROMOTION", active.getId());
        activate(promoted, active);
        if (rank(target) > rank(current)) {
            promotionEvidenceStore.recordStrategyPromotion(promoted, release, current, target, qualityGate);
        }
        return toDto(promoted, baseline);
    }

    @Transactional
    public ReviewStrategyPolicyDto rollback(long snapshotId, long expectedSnapshotId) {
        ReviewStrategyPolicySnapshot target = snapshotMapper.selectById(snapshotId);
        if (target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review strategy snapshot not found: " + snapshotId);
        }
        ReviewStrategyRelease release = toRelease(target);
        if (!release.supportsRuntimeVersions()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review strategy snapshot uses versions unsupported by the current runtime"
            );
        }
        ReviewStrategyPolicySnapshot active = requireActiveForUpdate();
        requireExpectedSnapshot(active, expectedSnapshotId);
        ReviewStrategyPolicySnapshot restored = copy(
            target,
            release.enforcementMode(),
            "ROLLBACK",
            target.getId()
        );
        activate(restored, active);
        return toDto(restored, qualityBaselineService.loadBaseline());
    }

    private void validatePromotion(
        ReviewStrategyRelease release,
        EnforcementMode current,
        EnforcementMode target,
        ReviewRuleQualityGateDto qualityGate
    ) {
        if (rank(target) < rank(current)) {
            return;
        }
        if (!release.supportsRuntimeVersions() || !release.replayVerified()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Strategy replay must pass before promotion");
        }
        if (current == EnforcementMode.OBSERVE && target == EnforcementMode.BLOCK) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Strategy must pass COMMENT before BLOCK");
        }
        if (target == EnforcementMode.COMMENT && !qualityGate.commentEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "At least one explicit labeled sample is required before COMMENT"
            );
        }
        if (target == EnforcementMode.BLOCK && !qualityGate.blockEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "BLOCK quality gate failed: " + String.join(",", qualityGate.blockers())
            );
        }
    }

    private void activate(
        ReviewStrategyPolicySnapshot snapshot,
        ReviewStrategyPolicySnapshot expectedActive
    ) {
        int updated = snapshotMapper.update(
            null,
            new LambdaUpdateWrapper<ReviewStrategyPolicySnapshot>()
                .eq(ReviewStrategyPolicySnapshot::getId, expectedActive.getId())
                .eq(ReviewStrategyPolicySnapshot::getActive, true)
                .set(ReviewStrategyPolicySnapshot::getActive, false)
        );
        if (updated != 1) {
            throw strategyConflict();
        }
        snapshotMapper.insert(snapshot);
    }

    private ReviewStrategyPolicySnapshot copy(
        ReviewStrategyPolicySnapshot source,
        EnforcementMode enforcementMode,
        String changeType,
        Long sourceSnapshotId
    ) {
        ReviewStrategyPolicySnapshot target = new ReviewStrategyPolicySnapshot();
        target.setStrategyVersion(source.getStrategyVersion());
        target.setPromptVersion(source.getPromptVersion());
        target.setContextVersion(source.getContextVersion());
        target.setSchemaVersion(source.getSchemaVersion());
        target.setVerifierVersion(source.getVerifierVersion());
        target.setAggregationVersion(source.getAggregationVersion());
        target.setEnforcementMode(enforcementMode.name());
        target.setReplayVerified(source.getReplayVerified());
        target.setActive(true);
        target.setChangeType(changeType);
        target.setSourceSnapshotId(sourceSnapshotId);
        target.setCreatedAt(LocalDateTime.now());
        return target;
    }

    private ReviewStrategyPolicySnapshot requireActive() {
        ReviewStrategyPolicySnapshot active = snapshotMapper.selectOne(
            new LambdaQueryWrapper<ReviewStrategyPolicySnapshot>()
                .eq(ReviewStrategyPolicySnapshot::getActive, true)
                .orderByDesc(ReviewStrategyPolicySnapshot::getId)
                .last("limit 1")
        );
        if (active == null) {
            throw new IllegalStateException("Active review strategy policy snapshot is missing");
        }
        return active;
    }

    private ReviewStrategyPolicySnapshot requireActiveForUpdate() {
        ReviewStrategyPolicySnapshot active = snapshotMapper.selectActiveForUpdate();
        if (active == null) {
            throw new IllegalStateException("Active review strategy policy snapshot is missing");
        }
        return active;
    }

    private void requireExpectedSnapshot(ReviewStrategyPolicySnapshot active, long expectedSnapshotId) {
        if (!Objects.equals(active.getId(), expectedSnapshotId)) {
            throw strategyConflict();
        }
    }

    private BusinessException strategyConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "Review strategy changed; reload and retry");
    }

    private void validatePage(Long cursor, int pageSize) {
        if ((cursor != null && cursor < 1) || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid review strategy history page");
        }
    }

    private ReviewStrategyPolicyDto toDto(
        ReviewStrategyPolicySnapshot snapshot,
        ReviewQualityBaseline baseline
    ) {
        ReviewStrategyRelease release = toRelease(snapshot);
        return new ReviewStrategyPolicyDto(
            value(snapshot.getId(), 0),
            value(snapshot.getStrategyVersion(), 1),
            snapshot.getPromptVersion(),
            snapshot.getContextVersion(),
            snapshot.getSchemaVersion(),
            snapshot.getVerifierVersion(),
            snapshot.getAggregationVersion(),
            lower(snapshot.getEnforcementMode()),
            Boolean.TRUE.equals(snapshot.getReplayVerified()),
            Boolean.TRUE.equals(snapshot.getActive()),
            snapshot.getChangeType(),
            snapshot.getSourceSnapshotId(),
            snapshot.getCreatedAt() == null ? null : snapshot.getCreatedAt().format(DATE_TIME_FORMATTER),
            lifecycleGate.evaluate(release, baseline.groups())
        );
    }

    private ReviewStrategyRelease toRelease(ReviewStrategyPolicySnapshot snapshot) {
        return new ReviewStrategyRelease(
            value(snapshot.getId(), 0),
            value(snapshot.getStrategyVersion(), 1),
            snapshot.getPromptVersion(),
            snapshot.getContextVersion(),
            snapshot.getSchemaVersion(),
            snapshot.getVerifierVersion(),
            snapshot.getAggregationVersion(),
            EnforcementMode.from(snapshot.getEnforcementMode()),
            Boolean.TRUE.equals(snapshot.getReplayVerified())
        );
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
