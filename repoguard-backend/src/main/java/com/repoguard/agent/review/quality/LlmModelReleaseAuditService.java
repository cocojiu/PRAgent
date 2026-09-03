package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditExportDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditVerificationDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Query, verification and bounded export operations for immutable release audit evidence. */
final class LlmModelReleaseAuditService {
    private static final int MAX_EXPORT_ROWS = 1_000;
    private static final List<String> ACTIONS = List.of(
        "REGISTER_SHADOW", "PROMOTE", "REPLACE_ACTIVE", "ROLLBACK", "AUTO_ROLLBACK");

    private final LlmModelReleaseRepository releaseRepository;
    private final ObjectMapper objectMapper;

    LlmModelReleaseAuditService(LlmModelReleaseRepository releaseRepository, ObjectMapper objectMapper) {
        this.releaseRepository = releaseRepository;
        this.objectMapper = objectMapper;
    }

    PageResponse<LlmModelReleaseAuditDto> list(Long releaseId, String releaseKey, String operator,
        String action, String from, String to, int page, int pageSize) {
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        LlmModelReleaseRepository.AuditFilter filter = auditFilter(releaseId, releaseKey, operator, action, from, to);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        long total = releaseRepository.countAudits(tenantId, filter);
        long offsetLong = (long) (safePage - 1) * safePageSize;
        int offset = offsetLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offsetLong;
        List<LlmModelReleaseAuditDto> items = total <= offset
            ? List.of()
            : releaseRepository.findAudits(tenantId, filter, offset, safePageSize).stream()
                .map(this::toDto).toList();
        return new PageResponse<>(items, total, null, offsetLong + items.size() < total);
    }

    LlmModelReleaseAuditVerificationDto verify(long auditId) {
        if (auditId < 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "审计记录编号必须为正数");
        LlmModelReleaseRepository.ReleaseAudit audit = releaseRepository.findAuditById(
            TenantContext.currentTenantIdOrDefault(), auditId);
        if (audit == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "发布审计记录不存在");
        AuditHashCheck check = auditHash(audit);
        return new LlmModelReleaseAuditVerificationDto(audit.id(), audit.releaseId(), audit.releaseKey(),
            audit.eventHash(), check.calculatedHash(), check.valid(), check.status());
    }

    LlmModelReleaseAuditExportDto export(Long releaseId, String releaseKey, String operator,
        String action, String from, String to, String format) {
        String normalizedFormat = normalizeExportFormat(format);
        LlmModelReleaseRepository.AuditFilter filter = auditFilter(releaseId, releaseKey, operator, action, from, to);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        long total = releaseRepository.countAudits(tenantId, filter);
        if (total > MAX_EXPORT_ROWS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布审计导出最多支持 1000 条，请缩小筛选范围");
        }
        List<LlmModelReleaseRepository.ReleaseAudit> audits = total == 0
            ? List.of()
            : releaseRepository.findAudits(tenantId, filter, 0, (int) total);
        String content = "csv".equals(normalizedFormat) ? auditCsv(audits) : auditJson(audits);
        return new LlmModelReleaseAuditExportDto(normalizedFormat, (long) audits.size(), sha256(content), content);
    }

    private LlmModelReleaseRepository.AuditFilter auditFilter(Long releaseId, String releaseKey,
        String operator, String action, String from, String to) {
        if (releaseId != null && releaseId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布编号必须为正数");
        }
        LocalDateTime fromTime = parseTime(from, "from");
        LocalDateTime toTime = parseTime(to, "to");
        if (fromTime != null && toTime != null && !fromTime.isBefore(toTime)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审计时间范围必须满足 from < to");
        }
        return new LlmModelReleaseRepository.AuditFilter(releaseId, normalizeText(releaseKey, 128),
            normalizeText(operator, 128), normalizeAction(action), fromTime, toTime);
    }

    private String normalizeText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审计筛选条件长度超限");
        }
        return normalized;
    }

    private String normalizeAction(String value) {
        String normalized = normalizeText(value, 32);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的发布审计动作");
        }
        return normalized;
    }

    private LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
            } catch (DateTimeParseException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 必须是 ISO-8601 时间");
            }
        }
    }

    private int normalizePage(int page) {
        if (page < 1 || page > 10_000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审计页码必须在 1～10000 之间");
        }
        return page;
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审计每页条数必须在 1～100 之间");
        }
        return pageSize;
    }

    private String normalizeExportFormat(String format) {
        String normalized = format == null || format.isBlank() ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (!List.of("json", "csv").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审计导出格式仅支持 JSON 或 CSV");
        }
        return normalized;
    }

    private LlmModelReleaseAuditDto toDto(LlmModelReleaseRepository.ReleaseAudit audit) {
        AuditHashCheck check = auditHash(audit);
        return new LlmModelReleaseAuditDto(audit.id(), audit.releaseId(), audit.releaseKey(), audit.action(),
            audit.fromState(), audit.toState(), audit.trafficPercent(), audit.operator(), audit.reason(),
            audit.detailsJson(), audit.eventHash(), audit.createdAt(), check.valid(), check.status());
    }

    private AuditHashCheck auditHash(LlmModelReleaseRepository.ReleaseAudit audit) {
        if (audit.eventHash() == null || !audit.eventHash().matches("(?i)^[0-9a-f]{64}$")) {
            return new AuditHashCheck("", false, "MALFORMED_HASH");
        }
        String calculated = sha256(audit.action(), audit.releaseId(), audit.releaseKey(),
            audit.detailsJson(), audit.reason());
        boolean valid = calculated.equalsIgnoreCase(audit.eventHash());
        return new AuditHashCheck(calculated, valid, valid ? "VALID" : "HASH_MISMATCH");
    }

    private String auditJson(List<LlmModelReleaseRepository.ReleaseAudit> audits) {
        return json(audits.stream().map(this::toExportRow).toList());
    }

    private String auditCsv(List<LlmModelReleaseRepository.ReleaseAudit> audits) {
        StringBuilder csv = new StringBuilder("id,releaseId,releaseKey,action,fromState,toState,trafficPercent,operator,eventHash,calculatedHash,createdAt,hashValid,hashStatus\n");
        for (LlmModelReleaseRepository.ReleaseAudit audit : audits) {
            AuditExportRow row = toExportRow(audit);
            csv.append(csvRow(row.id(), row.releaseId(), row.releaseKey(), row.action(), row.fromState(), row.toState(),
                row.trafficPercent(), row.operator(), row.eventHash(), row.calculatedHash(), row.createdAt(), row.hashValid(),
                row.hashStatus())).append('\n');
        }
        return csv.toString();
    }

    private AuditExportRow toExportRow(LlmModelReleaseRepository.ReleaseAudit audit) {
        AuditHashCheck check = auditHash(audit);
        return new AuditExportRow(audit.id(), audit.releaseId(), audit.releaseKey(), audit.action(), audit.fromState(),
            audit.toState(), audit.trafficPercent(), audit.operator(), audit.eventHash(), check.calculatedHash(),
            audit.createdAt(), check.valid(), check.status());
    }

    private String csvRow(Object... values) {
        return Arrays.stream(values).map(value -> csvCell(value == null ? "" : value.toString()))
            .reduce((left, right) -> left + "," + right).orElse("");
    }

    private String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("发布审计导出失败", ex);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for release audit exports", ex);
        }
    }

    static String sha256(String action, long releaseId, String releaseKey, String detailsJson, String reason) {
        String canonical = String.join("|", safe(action), Long.toString(releaseId), safe(releaseKey),
            safe(detailsJson), safe(reason));
        return sha256(canonical);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record AuditHashCheck(String calculatedHash, boolean valid, String status) {
    }

    private record AuditExportRow(Long id, Long releaseId, String releaseKey, String action, String fromState,
        String toState, Integer trafficPercent, String operator, String eventHash, String calculatedHash,
        LocalDateTime createdAt, boolean hashValid, String hashStatus) {
    }
}
