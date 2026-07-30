package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.DataRetentionCleanupAudit;
import com.repoguard.agent.mapper.DataRetentionCleanupAuditMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataRetentionCleanupAuditQueryServiceTest {

    private final DataRetentionCleanupAuditMapper auditMapper = org.mockito.Mockito.mock(
        DataRetentionCleanupAuditMapper.class
    );
    private final DataRetentionCleanupAuditQueryService service = new DataRetentionCleanupAuditQueryService(auditMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            DataRetentionCleanupAudit.class
        );
    }

    @Test
    void constructorRejectsMissingMapper() {
        assertThatThrownBy(() -> new DataRetentionCleanupAuditQueryService(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditMapper");
    }

    @Test
    void listAuditsMapsPagedRecordsAndNormalizesFilters() {
        Page<DataRetentionCleanupAudit> page = Page.of(2, 10);
        page.setRecords(List.of(audit()));
        page.setTotal(1);
        when(auditMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listAudits(
            2,
            10,
            " Execute ",
            " completed ",
            " backup://mysql/prod/2026-07-07T22:00:00 "
        );

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(77L);
        assertThat(result.items().getFirst().mode()).isEqualTo("execute");
        assertThat(result.items().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(result.items().getFirst().cutoffTime()).isEqualTo("2026-04-08 22:00:00");
        assertThat(result.items().getFirst().completedAt()).isEqualTo("2026-07-07 22:00:02");

        ArgumentCaptor<Page<DataRetentionCleanupAudit>> pageCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<LambdaQueryWrapper<DataRetentionCleanupAudit>> wrapperCaptor = ArgumentCaptor.captor();
        verify(auditMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("ORDER BY");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs())
            .containsValue("execute")
            .containsValue("COMPLETED")
            .containsValue("backup://mysql/prod/2026-07-07T22:00:00");
    }

    private DataRetentionCleanupAudit audit() {
        DataRetentionCleanupAudit audit = new DataRetentionCleanupAudit();
        audit.setId(77L);
        audit.setMode("execute");
        audit.setStatus("COMPLETED");
        audit.setRetentionDays(90);
        audit.setMaxTasks(500);
        audit.setBackupReference("backup://mysql/prod/2026-07-07T22:00:00");
        audit.setCutoffTime(LocalDateTime.of(2026, 4, 8, 22, 0));
        audit.setCandidateTasks(10L);
        audit.setSelectedTasks(2);
        audit.setDeletedBatchItems(1);
        audit.setDeletedPublications(1);
        audit.setDeletedBatches(1);
        audit.setDeletedChangedFiles(2);
        audit.setDeletedTimelines(2);
        audit.setDeletedFindings(2);
        audit.setDeletedTasks(2);
        audit.setCreatedAt(LocalDateTime.of(2026, 7, 7, 22, 0));
        audit.setCompletedAt(LocalDateTime.of(2026, 7, 7, 22, 0, 2));
        audit.setUpdatedAt(LocalDateTime.of(2026, 7, 7, 22, 0, 2));
        return audit;
    }
}
