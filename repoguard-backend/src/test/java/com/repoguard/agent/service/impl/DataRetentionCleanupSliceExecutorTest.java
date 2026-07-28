package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.retention.DataRetentionArchiveWriter;
import com.repoguard.agent.retention.DataRetentionDeleteExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class DataRetentionCleanupSliceExecutorTest {

    private final DataRetentionArchiveWriter archiveWriter = org.mockito.Mockito.mock(DataRetentionArchiveWriter.class);
    private final DataRetentionDeleteExecutor deleteExecutor = org.mockito.Mockito.mock(DataRetentionDeleteExecutor.class);
    private final DataRetentionCleanupSliceExecutor sliceExecutor = new DataRetentionCleanupSliceExecutor(
        archiveWriter,
        deleteExecutor
    );

    @Test
    void archivesBeforeDeletingAndReturnsDeletionCounts() {
        when(deleteExecutor.delete(List.of(7L, 9L)))
            .thenReturn(new DataRetentionDeleteExecutor.DeletionResult(3, 2, 1, 4, 5, 6, 2));

        DataRetentionDeleteExecutor.DeletionResult result = sliceExecutor.archiveAndDelete(
            42L,
            "backup://mysql/prod/2026-07-07T22:00:00",
            List.of(7L, 9L)
        );

        assertThat(result).isEqualTo(new DataRetentionDeleteExecutor.DeletionResult(3, 2, 1, 4, 5, 6, 2));
        InOrder order = inOrder(archiveWriter, deleteExecutor);
        order.verify(archiveWriter).write(42L, "backup://mysql/prod/2026-07-07T22:00:00", List.of(7L, 9L));
        order.verify(deleteExecutor).delete(List.of(7L, 9L));
    }

    @Test
    void doesNotDeleteWhenArchiveSummaryFails() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("archive unavailable");
        org.mockito.Mockito.doThrow(failure).when(archiveWriter).write(
            42L,
            "backup://mysql/prod/2026-07-07T22:00:00",
            List.of(11L)
        );

        assertThatThrownBy(() -> sliceExecutor.archiveAndDelete(
            42L,
            "backup://mysql/prod/2026-07-07T22:00:00",
            List.of(11L)
        ))
            .isSameAs(failure);

        verify(deleteExecutor, never()).delete(org.mockito.Mockito.anyList());
    }

    @Test
    void constructorRejectsMissingArchiveWriter() {
        assertThatThrownBy(() -> new DataRetentionCleanupSliceExecutor(null, deleteExecutor))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("archiveWriter");
    }

    @Test
    void constructorRejectsMissingDeleteExecutor() {
        assertThatThrownBy(() -> new DataRetentionCleanupSliceExecutor(archiveWriter, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("deleteExecutor");
    }

    @Test
    void eachCleanupSliceKeepsRequiresNewTransactionContract() throws Exception {
        Transactional transactional = DataRetentionCleanupSliceExecutor.class
            .getMethod("archiveAndDelete", long.class, String.class, List.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
