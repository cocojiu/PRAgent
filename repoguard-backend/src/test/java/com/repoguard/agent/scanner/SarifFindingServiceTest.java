package com.repoguard.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SarifFindingServiceTest {

    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final SarifFindingService service = new SarifFindingService(new ObjectMapper(), taskMapper, findingMapper);

    @Test
    void importsSarifResultIntoTenantScopedFindingAndSkipsUnsafeLocations() {
        when(taskMapper.selectById(9L)).thenReturn(new ReviewTask());
        String sarif = """
            {
              "version":"2.1.0",
              "runs":[{"tool":{"driver":{"rules":[{"id":"SAST-1","help":{"text":"Use a safe API"}}]}},
                "results":[
                  {"ruleId":"SAST-1","level":"error","message":{"text":"Unsafe call"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/App.java"},"region":{"startLine":7}}}]},
                  {"ruleId":"SAST-2","locations":[{"physicalLocation":{"artifactLocation":{"uri":"../../secret.txt"},"region":{"startLine":1}}}]}
                ]}]
            }
            """;

        var result = service.importFindings(9L, new SarifImportRequest(sarif));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("SAST-1");
            assertThat(finding.filePath()).isEqualTo("src/App.java");
            assertThat(finding.severity()).isEqualTo("HIGH");
        });
        verify(findingMapper).markCurrentAttemptHistorical(9L);
        verify(findingMapper).insert(any(ReviewFinding.class));
    }

    @Test
    void exportsCurrentFindingsAsSarifDocument() {
        when(taskMapper.selectById(9L)).thenReturn(new ReviewTask());
        ReviewFinding finding = new ReviewFinding();
        finding.setId(2L);
        finding.setRuleId("SAST-1");
        finding.setFilePath("src/App.java");
        finding.setLineNumber(7);
        finding.setSeverity("HIGH");
        finding.setMessage("Unsafe call");
        finding.setCurrentAttempt(true);
        finding.setCategory("FINDING");
        when(findingMapper.selectList(any())).thenReturn(List.of(finding));

        var document = service.exportFindings(9L);

        assertThat(document.version()).isEqualTo("2.1.0");
        assertThat(document.runs()).hasSize(1);
        assertThat(document.runs().getFirst()).containsKey("results");
        verify(findingMapper, never()).insert(any(ReviewFinding.class));
    }
}
