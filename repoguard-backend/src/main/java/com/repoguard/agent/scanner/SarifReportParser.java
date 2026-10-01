package com.repoguard.agent.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportedFindingDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.util.StringUtils;

/** Parses bounded, untrusted reports without a database transaction. */
final class SarifReportParser {
    private static final String SARIF_VERSION = "2.1.0";
    private static final int MAX_RUNS = 20;
    private static final int MAX_RESULTS = 5_000;
    private static final int MAX_RULE_ID_LENGTH = 64;
    private final ObjectMapper objectMapper;
    SarifReportParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    ParsedReport prepare(SarifImportRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF content is required");
        }
        if (request.content().length() > 2_000_000) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF content exceeds 2000000 characters");
        }
        JsonNode root = parse(request.content());
        if (!SARIF_VERSION.equals(root.path("version").asText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only SARIF version 2.1.0 is supported");
        }
        JsonNode runs = root.path("runs");
        if (!runs.isArray() || runs.isEmpty() || runs.size() > MAX_RUNS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF runs must be a non-empty array of at most 20 items");
        }
        ToolMetadata tool = toolMetadata(runs);
        String fingerprint = fingerprint(request.content());
        List<SarifImportedFindingDto> imported = new ArrayList<>();
        List<ParsedResult> parsedResults = new ArrayList<>();
        int skipped = 0;
        int totalResults = 0;
        for (JsonNode run : runs) {
            Map<String, String> ruleHelp = ruleHelp(run.path("tool").path("driver").path("rules"));
            JsonNode results = run.path("results");
            if (!results.isArray()) {
                continue;
            }
            totalResults += results.size();
            if (totalResults > MAX_RESULTS) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF contains too many results");
            }
            for (JsonNode result : results) {
                ParsedResult parsed = parseResult(result, ruleHelp);
                if (parsed == null) {
                    skipped++;
                    continue;
                }
                parsedResults.add(parsed);
                imported.add(new SarifImportedFindingDto(
                    parsed.ruleId(), parsed.filePath(), parsed.lineNumber(), parsed.severity(), parsed.message()
                ));
            }
        }
        return new ParsedReport(tool.name(), tool.version(), fingerprint, parsedResults, skipped, imported);
    }
    private JsonNode parse(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid SARIF JSON");
        }
    }
    private ParsedResult parseResult(JsonNode result, Map<String, String> ruleHelp) {
        String ruleId = result.path("ruleId").asText("").trim();
        if (!StringUtils.hasText(ruleId) || ruleId.length() > MAX_RULE_ID_LENGTH) {
            return null;
        }
        JsonNode location = result.path("locations").isArray() && result.path("locations").size() > 0
            ? result.path("locations").get(0).path("physicalLocation")
            : null;
        if (location == null || location.isMissingNode()) {
            return null;
        }
        String filePath = sanitizePath(location.path("artifactLocation").path("uri").asText(""));
        if (!StringUtils.hasText(filePath)) {
            return null;
        }
        int line = location.path("region").path("startLine").asInt(0);
        if (line < 1) {
            return null;
        }
        String message = result.path("message").path("text").asText("").trim();
        if (!StringUtils.hasText(message)) {
            message = ruleHelp.getOrDefault(ruleId, "SARIF scanner reported a finding");
        }
        return new ParsedResult(ruleId, filePath, line, severity(result.path("level").asText("")), message,
            ruleHelp.getOrDefault(ruleId, ""));
    }
    private Map<String, String> ruleHelp(JsonNode rules) {
        Map<String, String> help = new LinkedHashMap<>();
        if (!rules.isArray()) {
            return help;
        }
        for (JsonNode rule : rules) {
            String id = rule.path("id").asText("").trim();
            if (!StringUtils.hasText(id) || id.length() > MAX_RULE_ID_LENGTH) {
                continue;
            }
            String value = rule.path("help").path("text").asText("");
            if (!StringUtils.hasText(value)) {
                value = rule.path("shortDescription").path("text").asText("");
            }
            help.put(id, value.trim());
        }
        return help;
    }
    private String sanitizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
            || normalized.startsWith("file:") || normalized.contains("..")) {
            return "";
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.length() > 1024 ? "" : normalized;
    }
    private String severity(String level) {
        return switch (level == null ? "" : level.trim().toLowerCase()) {
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "note" -> "LOW";
            default -> "INFO";
        };
    }
    private ToolMetadata toolMetadata(JsonNode runs) {
        Set<String> names = new TreeSet<>();
        Set<String> versions = new TreeSet<>();
        for (JsonNode run : runs) {
            JsonNode driver = run.path("tool").path("driver");
            String name = metadataValue(driver.path("name").asText(""), 128);
            String version = metadataValue(driver.path("version").asText(""), 64);
            if (version.isEmpty()) {
                version = metadataValue(driver.path("semanticVersion").asText(""), 64);
            }
            if (StringUtils.hasText(name)) {
                names.add(name);
            }
            if (StringUtils.hasText(version)) {
                versions.add(version);
            }
        }
        return new ToolMetadata(
            canonicalMetadata(names, "unknown", 128),
            canonicalMetadata(versions, "", 64)
        );
    }
    private String canonicalMetadata(Set<String> values, String fallback, int maxLength) {
        if (values.isEmpty()) {
            return fallback;
        }
        String normalized = String.join(",", values);
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
    private String metadataValue(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
    String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
    record ParsedReport(String toolName, String toolVersion, String fingerprint, List<ParsedResult> results,
                        int skipped, List<SarifImportedFindingDto> findings) {
        ParsedReport { results = List.copyOf(results); findings = List.copyOf(findings); }
    }
    private record ToolMetadata(String name, String version) { }
    record ParsedResult(String ruleId, String filePath, int lineNumber, String severity, String message, String help) { }
}
