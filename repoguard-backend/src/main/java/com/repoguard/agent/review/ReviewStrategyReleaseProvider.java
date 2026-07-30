package com.repoguard.agent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewStrategyPolicySnapshotMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewStrategyReleaseProvider {

    private final ReviewStrategyPolicySnapshotMapper snapshotMapper;

    public ReviewStrategyReleaseProvider(ReviewStrategyPolicySnapshotMapper snapshotMapper) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
    }

    public ReviewStrategyRelease getActiveRelease() {
        ReviewStrategyPolicySnapshot snapshot = snapshotMapper.selectOne(
            new LambdaQueryWrapper<ReviewStrategyPolicySnapshot>()
                .eq(ReviewStrategyPolicySnapshot::getActive, true)
                .orderByDesc(ReviewStrategyPolicySnapshot::getId)
                .last("limit 1")
        );
        if (snapshot == null) {
            return ReviewStrategyRelease.observeDefaults();
        }
        ReviewStrategyRelease release = new ReviewStrategyRelease(
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
        if (!release.supportsRuntimeVersions()) {
            return ReviewStrategyRelease.observeDefaults();
        }
        return release;
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}
