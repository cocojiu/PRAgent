package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.mapper.ReviewTaskArchiveSummaryMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataRetentionArchiveWriterTest {

    private final ReviewTaskArchiveSummaryMapper archiveSummaryMapper =
        org.mockito.Mockito.mock(ReviewTaskArchiveSummaryMapper.class);
    private final DataRetentionArchiveWriter archiveWriter = new DataRetentionArchiveWriter(archiveSummaryMapper);

    @Test
    void writesArchiveSummariesForTheSelectedTasks() {
        archiveWriter.write(42L, "backup://mysql/prod/snapshot-42", List.of(7L, 9L));

        verify(archiveSummaryMapper).insertArchiveSummaries(
            42L,
            "backup://mysql/prod/snapshot-42",
            List.of(7L, 9L)
        );
    }

    @Test
    void rejectsEmptySelectionWithoutCallingTheMapper() {
        assertThatThrownBy(() -> archiveWriter.write(42L, "backup://mysql/prod/snapshot-42", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("taskIds");

        verify(archiveSummaryMapper, never()).insertArchiveSummaries(
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.anyString(),
            org.mockito.Mockito.anyList()
        );
    }
}
