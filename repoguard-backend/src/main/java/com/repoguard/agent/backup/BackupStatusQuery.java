package com.repoguard.agent.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.BackupStatusResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reads only an explicitly configured, read-only metadata file; never invokes backup tooling. */
@Service
public class BackupStatusQuery {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> REASONS = Set.of("backup_storage_budget_exhausted",
        "credential_permissions_too_broad", "invalid_backup_password_format", "backup_timeout",
        "backup_command_failed", "invalid_completed_backup_path", "completed_backup_not_verified",
        "backup_name_collision", "retention_candidate_changed", "service_interrupted",
        "unsafe_verification_marker", "unsafe_archive_path", "invalid_archive_checksum", "archive_checksum_mismatch");
    private final ObjectMapper json;
    private final String file;
    private final int maxAgeHours;

    public BackupStatusQuery(ObjectMapper json,
                             @Value("${repoguard.backup-status.file:}") String file,
                             @Value("${repoguard.backup-status.max-age-hours:30}") int maxAgeHours) {
        if (maxAgeHours < 1 || maxAgeHours > 168) throw new IllegalArgumentException("Invalid backup status freshness");
        this.json = json;
        this.file = file;
        this.maxAgeHours = maxAgeHours;
    }

    public BackupStatusResponse read() {
        Instant now = Instant.now();
        if (file == null || file.isBlank()) return empty("DISABLED", now);
        try {
            Path path = Path.of(file);
            if (!path.isAbsolute() || !path.normalize().equals(path.toRealPath())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return empty("UNREADABLE_OR_INVALID", now);
            byte[] raw;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                raw = input.readNBytes(MAX_BYTES + 1);
            }
            if (raw.length > MAX_BYTES) return empty("UNREADABLE_OR_INVALID", now);
            JsonNode record = json.readTree(raw);
            if (record == null || !"mysql-logical-snapshot".equals(record.path("scope").asText())) {
                return empty("UNREADABLE_OR_INVALID", now);
            }
            long started = number(record, "startedAtUnix");
            long epoch = now.getEpochSecond();
            if (started <= 0 || started > epoch) return empty("UNREADABLE_OR_INVALID", now);
            String phase = record.path("state").asText();
            if ("running".equals(phase)) {
                return result(epoch - started > 960 ? "RUN_OVERRUN" : "RUNNING_RECORDED", now,
                    started, null, epoch - started, null, null, null);
            }
            long finished = number(record, "finishedAtUnix");
            if (finished < started || finished > epoch) return empty("UNREADABLE_OR_INVALID", now);
            long age = epoch - finished;
            if ("failed".equals(phase)) {
                String reason = record.path("reason").asText();
                return result("FAILED", now, started, finished, age, null, null,
                    REASONS.contains(reason) ? reason : "unspecified_failure");
            }
            if (!"success".equals(phase)) return empty("UNREADABLE_OR_INVALID", now);
            long retained = number(record, "retained"), bytes = number(record.path("archive"), "bytes");
            if (retained < 1 || retained > 100 || bytes < 1 || !record.path("storageBudgetExceeded").isBoolean()) {
                return empty("UNREADABLE_OR_INVALID", now);
            }
            String state = record.path("storageBudgetExceeded").booleanValue() ? "STORAGE_LIMIT"
                : age > maxAgeHours * 3600L ? "STALE" : "SUCCESS_RECORDED";
            return result(state, now, started, finished, age, (int) retained, bytes, null);
        } catch (NoSuchFileException failure) {
            return empty("MISSING", now);
        } catch (Exception failure) {
            return empty("UNREADABLE_OR_INVALID", now); // Never disclose file names or parser payloads.
        }
    }

    private long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid metadata");
        return value.longValue();
    }

    private BackupStatusResponse empty(String status, Instant now) {
        return result(status, now, null, null, null, null, null, null);
    }

    private BackupStatusResponse result(String status, Instant now, Long started, Long finished,
                                        Long age, Integer retained, Long bytes, String reason) {
        return new BackupStatusResponse(status, now, started == null ? null : Instant.ofEpochSecond(started),
            finished == null ? null : Instant.ofEpochSecond(finished), age, maxAgeHours, retained, bytes,
            reason, false, false, false, false);
    }
}
