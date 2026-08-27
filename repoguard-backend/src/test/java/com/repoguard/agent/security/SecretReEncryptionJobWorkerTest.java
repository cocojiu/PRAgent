package com.repoguard.agent.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.SecretReEncryptionJob;
import com.repoguard.agent.mapper.SecretReEncryptionJobMapper;
import com.repoguard.agent.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretReEncryptionJobWorkerTest {

    private final SecretReEncryptionJobMapper jobMapper = Mockito.mock(SecretReEncryptionJobMapper.class);
    private final SecretReEncryptionJobService jobService = Mockito.mock(SecretReEncryptionJobService.class);
    private final SecretReEncryptionJobBatchProcessor batchProcessor =
        Mockito.mock(SecretReEncryptionJobBatchProcessor.class);
    private final SecretReEncryptionProperties properties = new SecretReEncryptionProperties();
    private final SecretReEncryptionJobWorker worker = new SecretReEncryptionJobWorker(
        jobMapper,
        jobService,
        batchProcessor,
        properties
    );

    @Test
    void claimsAndProcessesOneDueJob() {
        when(jobMapper.selectDueJob(anyLong(), any())).thenReturn(job(7L));
        when(jobMapper.claim(any(), anyLong(), anyString(), any(), any())).thenReturn(1);

        worker.processDueJob();

        verify(jobMapper).selectDueJob(Mockito.eq(TenantContext.DEFAULT_TENANT_ID), any());
        verify(jobMapper).claim(
            Mockito.eq(7L),
            Mockito.eq(TenantContext.DEFAULT_TENANT_ID),
            anyString(),
            any(),
            any()
        );
        verify(batchProcessor).process(Mockito.eq(7L), anyString());
        verify(jobService, never()).markInfrastructureFailure(any(), anyString(), any());
    }

    @Test
    void usesCurrentTenantForDueSelectionAndClaim() {
        when(jobMapper.selectDueJob(anyLong(), any())).thenReturn(job(8L));
        when(jobMapper.claim(any(), anyLong(), anyString(), any(), any())).thenReturn(1);

        try (TenantContext.Scope _ = TenantContext.withTenant(23L)) {
            worker.processDueJob();
        }

        verify(jobMapper).selectDueJob(Mockito.eq(23L), any());
        verify(jobMapper).claim(Mockito.eq(8L), Mockito.eq(23L), anyString(), any(), any());
    }

    @Test
    void skipsProcessingWhenAnotherWorkerWinsTheClaim() {
        when(jobMapper.selectDueJob(anyLong(), any())).thenReturn(job(7L));
        when(jobMapper.claim(any(), anyLong(), anyString(), any(), any())).thenReturn(0);

        worker.processDueJob();

        verify(batchProcessor, never()).process(any(), anyString());
    }

    @Test
    void recordsInfrastructureFailureWithoutLeakingTheExceptionMessage() {
        when(jobMapper.selectDueJob(anyLong(), any())).thenReturn(job(7L));
        when(jobMapper.claim(any(), anyLong(), anyString(), any(), any())).thenReturn(1);
        Mockito.doThrow(new IllegalStateException("sensitive database detail"))
            .when(batchProcessor)
            .process(Mockito.eq(7L), anyString());
        when(jobService.markInfrastructureFailure(Mockito.eq(7L), anyString(), any())).thenReturn(true);

        worker.processDueJob();

        verify(jobService).markInfrastructureFailure(
            Mockito.eq(7L),
            anyString(),
            Mockito.isA(IllegalStateException.class)
        );
    }

    @Test
    void claimLossDoesNotConsumeARetryAttempt() {
        when(jobMapper.selectDueJob(anyLong(), any())).thenReturn(job(7L));
        when(jobMapper.claim(any(), anyLong(), anyString(), any(), any())).thenReturn(1);
        Mockito.doThrow(new SecretReEncryptionClaimLostException(7L))
            .when(batchProcessor)
            .process(Mockito.eq(7L), anyString());

        worker.processDueJob();

        verify(jobService, never()).markInfrastructureFailure(any(), anyString(), any());
    }

    private SecretReEncryptionJob job(Long id) {
        SecretReEncryptionJob job = new SecretReEncryptionJob();
        job.setId(id);
        return job;
    }
}
