package com.repoguard.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.service.DataRetentionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DataRetentionControllerTest {

    private final DataRetentionService dataRetentionService = Mockito.mock(DataRetentionService.class);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new DataRetentionController(dataRetentionService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void cleanupRejectsOverlongBackupReferenceBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/config/data-retention/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "retentionDays": 30,
                      "maxTasks": 100,
                      "execute": true,
                      "backupReference": "%s",
                      "confirmText": "CLEANUP"
                    }
                    """.formatted("x".repeat(129))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(dataRetentionService, never()).cleanup(any());
    }

    @Test
    void cleanupRejectsOverlongConfirmationTextBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/config/data-retention/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "retentionDays": 30,
                      "maxTasks": 100,
                      "execute": true,
                      "backupReference": "backup://mysql/prod/2026-07-07T22:00:00",
                      "confirmText": "%s"
                    }
                    """.formatted("x".repeat(33))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(dataRetentionService, never()).cleanup(any());
    }

    @Test
    void cleanupAcceptsBoundedConfirmationText() throws Exception {
        Mockito.when(dataRetentionService.cleanup(any())).thenReturn(new DataRetentionCleanupResponse(
            false,
            77L,
            30,
            100,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "2026-07-03 12:00:00",
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        ));

        mockMvc.perform(post("/api/v1/config/data-retention/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "retentionDays": 30,
                      "maxTasks": 100,
                      "execute": true,
                      "backupReference": "backup://mysql/prod/2026-07-07T22:00:00",
                      "confirmText": "CLEANUP"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cleanupBatchId").value(77))
            .andExpect(jsonPath("$.data.retentionDays").value(30))
            .andExpect(jsonPath("$.data.maxTasks").value(100))
            .andExpect(jsonPath("$.data.backupReference").value("backup://mysql/prod/2026-07-07T22:00:00"));
    }

    @Test
    void listCleanupAuditsReturnsPagedAuditRecords() throws Exception {
        Mockito.when(dataRetentionService.listCleanupAudits(
            2,
            50,
            "execute",
            "completed",
            "backup://mysql/prod/2026-07-07T22:00:00"
        )).thenReturn(new PageResponse<>(List.of(new DataRetentionCleanupAuditDto(
            77L,
            "execute",
            "COMPLETED",
            30,
            100,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "2026-07-03 12:00:00",
            3L,
            2,
            1,
            1,
            1,
            2,
            2,
            2,
            2,
            null,
            null,
            "2026-07-07 22:00:00",
            "2026-07-07 22:00:02",
            "2026-07-07 22:00:02"
        )), 1));

        mockMvc.perform(get("/api/v1/config/data-retention/cleanup-audits")
                .param("page", "2")
                .param("pageSize", "50")
                .param("mode", "execute")
                .param("status", "completed")
                .param("backupReference", "backup://mysql/prod/2026-07-07T22:00:00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(77))
            .andExpect(jsonPath("$.data.items[0].mode").value("execute"))
            .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.items[0].backupReference").value("backup://mysql/prod/2026-07-07T22:00:00"));

        verify(dataRetentionService).listCleanupAudits(
            2,
            50,
            "execute",
            "completed",
            "backup://mysql/prod/2026-07-07T22:00:00"
        );
    }
}
