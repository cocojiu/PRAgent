package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.entity.SecretReEncryptionJob;
import com.repoguard.agent.mapper.SecretReEncryptionJobItemMapper;
import com.repoguard.agent.mapper.SecretReEncryptionJobMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretReEncryptionJobServiceTest {

    private static final String ACTIVE_KEY = "Active-Encryption-Key-2026!Rotate-Primary";

    private final SecretReEncryptionJobMapper jobMapper = Mockito.mock(SecretReEncryptionJobMapper.class);
    private final SecretReEncryptionJobItemMapper itemMapper = Mockito.mock(SecretReEncryptionJobItemMapper.class);
    private final SecretCryptoService activeCrypto = new SecretCryptoService(
        ACTIVE_KEY,
        "active-2026",
        "Re-Encryption-Salt-2026!Primary",
        false
    );
    private final SecretReEncryptionProperties properties = new SecretReEncryptionProperties();
    private final SecretReEncryptionJobService service = new SecretReEncryptionJobService(
        jobMapper,
        itemMapper,
        activeCrypto,
        properties
    );

    @BeforeEach
    void setUp() {
        when(jobMapper.insert(any(SecretReEncryptionJob.class))).thenAnswer(invocation -> {
            SecretReEncryptionJob job = invocation.getArgument(0);
            job.setId(7L);
            return 1;
        });
    }

    @Test
    void startStoresKeyMaterialOnlyAsActiveCiphertextAndReturnsPendingSummary() {
        SecretReEncryptionRequest request = request(false, null);

        var result = service.start(request, 11L, "admin");

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.executed()).isFalse();
        assertThat(result.batchSize()).isEqualTo(100);
        assertThat(result.createdByUsername()).isEqualTo("admin");
        SecretReEncryptionJob stored = Mockito.mockingDetails(jobMapper).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("insert"))
            .map(invocation -> (SecretReEncryptionJob) invocation.getArgument(0))
            .findFirst()
            .orElseThrow();
        assertThat(stored.getSourceKeyCiphertext()).doesNotContain(request.sourceEncryptionKey());
        assertThat(stored.getTargetKeyCiphertext()).doesNotContain(request.targetEncryptionKey());
        assertThat(activeCrypto.decrypt(stored.getSourceKeyCiphertext())).isEqualTo(request.sourceEncryptionKey());
        assertThat(activeCrypto.decrypt(stored.getTargetKeyCiphertext())).isEqualTo(request.targetEncryptionKey());
    }

    @Test
    void executeRequiresConfirmationBeforeCreatingAJob() {
        assertThatThrownBy(() -> service.start(request(true, "WRONG"), 11L, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("confirmText must be RE-ENCRYPT when execute is true");

        verify(jobMapper, never()).insert(any(SecretReEncryptionJob.class));
    }

    @Test
    void duplicateActiveJobIsReportedAsConflict() {
        when(jobMapper.insert(any(SecretReEncryptionJob.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("active"));

        assertThatThrownBy(() -> service.start(request(false, null), 11L, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already active");
    }

    @Test
    void startRejectsEqualSourceAndTargetKeyIds() {
        SecretReEncryptionRequest request = new SecretReEncryptionRequest(
            "Old-Encryption-Key-2026!Rotate-Primary",
            "same-2026",
            "New-Encryption-Key-2026!Rotate-Primary",
            "same-2026",
            false,
            null
        );

        assertThatThrownBy(() -> service.start(request, 11L, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("targetKeyId must differ");

        verify(jobMapper, never()).insert(any(SecretReEncryptionJob.class));
    }

    @Test
    void startReportsInvalidTargetKeyMaterialAsBadRequest() {
        SecretReEncryptionRequest request = new SecretReEncryptionRequest(
            "Old-Encryption-Key-2026!Rotate-Primary",
            "old-2026",
            "too-short",
            "new-2026",
            false,
            null
        );

        assertThatThrownBy(() -> service.start(request, 11L, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at least 32 characters");

        verify(jobMapper, never()).insert(any(SecretReEncryptionJob.class));
    }

    @Test
    void pauseAndResumeUseGuardedStateTransitions() {
        SecretReEncryptionJob pending = pendingJob();
        SecretReEncryptionJob pausedJob = jobWithStatus("PAUSED");
        SecretReEncryptionJob resumed = pendingJob();
        when(jobMapper.selectById(7L)).thenReturn(pending, pausedJob, pausedJob, resumed);
        when(jobMapper.update(any(), any())).thenReturn(1);

        var paused = service.pause(7L);
        assertThat(paused.status()).isEqualTo("PAUSED");

        var resumedResult = service.resume(7L);
        assertThat(resumedResult.status()).isEqualTo("PENDING");
        verify(jobMapper, Mockito.times(2)).update(any(), any());
        verify(jobMapper, never()).updateById(any(SecretReEncryptionJob.class));
    }

    @Test
    void resumeReportsConflictWhenAnotherJobOccupiesTheActiveSlot() {
        when(jobMapper.selectById(7L)).thenReturn(jobWithStatus("FAILED"));
        when(jobMapper.update(any(), any())).thenThrow(new org.springframework.dao.DuplicateKeyException("active"));

        assertThatThrownBy(() -> service.resume(7L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Another secret re-encryption job");
    }

    @Test
    void listJobsReturnsNewestFirstPage() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<SecretReEncryptionJob>(1, 1);
        page.setRecords(List.of(pendingJob()));
        page.setTotal(3);
        when(jobMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listJobs(0, 1000);

        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    void listItemsBoundsPageSizeAndReturnsPagedRows() {
        when(jobMapper.selectById(7L)).thenReturn(pendingJob());
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.repoguard.agent.entity.SecretReEncryptionJobItem>(
            1,
            100
        );
        page.setRecords(List.of());
        page.setTotal(0);
        when(itemMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listItems(7L, 0, 1000);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        verify(itemMapper).selectPage(any(), any());
    }

    private SecretReEncryptionRequest request(boolean execute, String confirmText) {
        return new SecretReEncryptionRequest(
            "Old-Encryption-Key-2026!Rotate-Primary",
            "old-2026",
            "New-Encryption-Key-2026!Rotate-Primary",
            "new-2026",
            execute,
            confirmText
        );
    }

    private SecretReEncryptionJob pendingJob() {
        return jobWithStatus("PENDING");
    }

    private SecretReEncryptionJob jobWithStatus(String status) {
        SecretReEncryptionJob job = new SecretReEncryptionJob();
        job.setId(7L);
        job.setStatus(status);
        job.setMode("DRY_RUN");
        job.setBatchSize(100);
        job.setRetryCount(0);
        return job;
    }
}
