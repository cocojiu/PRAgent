package com.repoguard.agent.dto;

import java.util.List;
import java.util.Map;

/** JSON-compatible SARIF 2.1.0 document payload. */
public record SarifExportDto(
    String version,
    String schema,
    List<Map<String, Object>> runs
) {
}
