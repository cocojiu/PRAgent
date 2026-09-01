package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.DeclarativeRuleDryRunRequest;
import com.repoguard.agent.dto.DeclarativeRuleDryRunResponse;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.review.config.DeclarativeRuleDryRunService;
import com.repoguard.agent.scanner.SarifFindingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScannerAndDeclarativeControllerTest {

    @Test
    void declarativeRuleControllerWrapsDryRunResponse() {
        DeclarativeRuleDryRunService service = mock(DeclarativeRuleDryRunService.class);
        var expected = new DeclarativeRuleDryRunResponse("RG-CUSTOM-001", 7L, 1, 1, List.of());
        when(service.run("RG-CUSTOM-001", null)).thenReturn(expected);

        var response = new DeclarativeRuleController(service).dryRun("RG-CUSTOM-001", null);

        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void sarifControllerDelegatesImportAndExport() {
        SarifFindingService service = mock(SarifFindingService.class);
        var imported = new SarifImportResponse(7L, 1, 0, List.of());
        var exported = new SarifExportDto("2.1.0", "schema", List.of(Map.of()));
        var request = new SarifImportRequest("{}");
        when(service.importFindings(7L, request)).thenReturn(imported);
        when(service.exportFindings(7L)).thenReturn(exported);

        SarifController controller = new SarifController(service);

        assertThat(controller.importFindings(7L, request).data()).isEqualTo(imported);
        assertThat(controller.exportFindings(7L).data()).isEqualTo(exported);
    }
}
