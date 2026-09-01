package com.repoguard.agent.dto;

import java.util.List;

public record SarifImportResponse(
    Long taskId,
    int imported,
    int skipped,
    List<SarifImportedFindingDto> findings
) {
}
