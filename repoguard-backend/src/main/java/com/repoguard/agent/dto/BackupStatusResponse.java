package com.repoguard.agent.dto;

import java.time.Instant;

/** Execution-record projection only; no archive paths, credentials or recovery claims. */
public record BackupStatusResponse(
    String status, Instant checkedAt, Instant startedAt, Instant finishedAt,
    Long ageSeconds, int maxAgeHours, Integer retained, Long archiveBytes, String reasonCode,
    boolean restoreVerified, boolean archiveIntegrityChecked,
    boolean processLivenessChecked, boolean timerEnabledChecked
) { }
