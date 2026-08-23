package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewStrategyPolicySnapshotMapper;
import org.junit.jupiter.api.Test;

class ReviewStrategyReleaseProviderTest {

    private final ReviewStrategyPolicySnapshotMapper mapper = org.mockito.Mockito.mock(
        ReviewStrategyPolicySnapshotMapper.class
    );
    private final ReviewStrategyReleaseProvider provider = new ReviewStrategyReleaseProvider(mapper);

    @Test
    void returnsObserveDefaultsWhenNoActiveReleaseExists() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(provider.getActiveRelease()).isEqualTo(ReviewStrategyRelease.observeDefaults());
    }

    @Test
    void mapsVerifiedActiveReleaseAndDefaultsNullableNumbers() {
        ReviewStrategyPolicySnapshot snapshot = compatibleSnapshot();
        snapshot.setId(null);
        snapshot.setStrategyVersion(null);
        when(mapper.selectOne(any())).thenReturn(snapshot);

        ReviewStrategyRelease release = provider.getActiveRelease();

        assertThat(release.snapshotId()).isZero();
        assertThat(release.strategyVersion()).isEqualTo(1L);
        assertThat(release.enforcementMode()).isEqualTo(EnforcementMode.BLOCK);
        assertThat(release.replayVerified()).isTrue();
    }

    @Test
    void rejectsIncompatibleRuntimeVersionsByFallingBackToObserveDefaults() {
        ReviewStrategyPolicySnapshot snapshot = compatibleSnapshot();
        snapshot.setPromptVersion("future-prompt");
        when(mapper.selectOne(any())).thenReturn(snapshot);

        assertThat(provider.getActiveRelease()).isEqualTo(ReviewStrategyRelease.observeDefaults());
    }

    @Test
    void constructorRequiresSnapshotMapper() {
        assertThatThrownBy(() -> new ReviewStrategyReleaseProvider(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("snapshotMapper");
    }

    private ReviewStrategyPolicySnapshot compatibleSnapshot() {
        ReviewStrategyPolicySnapshot snapshot = new ReviewStrategyPolicySnapshot();
        snapshot.setId(9L);
        snapshot.setStrategyVersion(3L);
        snapshot.setPromptVersion(LlmReviewVersions.PROMPT);
        snapshot.setContextVersion(LlmReviewVersions.CONTEXT);
        snapshot.setSchemaVersion(LlmReviewVersions.SCHEMA);
        snapshot.setVerifierVersion(LlmReviewVersions.VERIFIER);
        snapshot.setAggregationVersion(ServerRiskAggregator.VERSION);
        snapshot.setEnforcementMode("BLOCK");
        snapshot.setReplayVerified(true);
        return snapshot;
    }
}
