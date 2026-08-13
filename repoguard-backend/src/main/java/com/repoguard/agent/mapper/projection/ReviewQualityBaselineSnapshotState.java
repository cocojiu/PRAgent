package com.repoguard.agent.mapper.projection;

import java.time.LocalDateTime;

public record ReviewQualityBaselineSnapshotState(
    long sourceVersion,
    long refreshedVersion,
    String baselinePayload,
    LocalDateTime calculatedAt
) {

    public boolean dirty() {
        return baselinePayload == null || refreshedVersion < sourceVersion;
    }
}
