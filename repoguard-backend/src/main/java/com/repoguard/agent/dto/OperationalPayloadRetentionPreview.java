package com.repoguard.agent.dto;

import java.time.LocalDateTime;

/** Aggregate preview only; no retained comment or diagnostic payload is returned. */
public record OperationalPayloadRetentionPreview(
    String table, boolean enabled, int retentionDays, LocalDateTime cutoff,
    long candidateCount, LocalDateTime oldestAt, long payloadBytes
) { }
