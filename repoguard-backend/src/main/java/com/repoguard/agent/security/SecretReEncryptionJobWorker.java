package com.repoguard.agent.security;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.entity.SecretReEncryptionJob;
import com.repoguard.agent.mapper.SecretReEncryptionJobMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class SecretReEncryptionJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretReEncryptionJobWorker.class);

    private final SecretReEncryptionJobMapper jobMapper;
    private final SecretReEncryptionJobService jobService;
    private final SecretReEncryptionJobBatchProcessor batchProcessor;
    private final SecretReEncryptionProperties properties;

    public SecretReEncryptionJobWorker(
        SecretReEncryptionJobMapper jobMapper,
        SecretReEncryptionJobService jobService,
        SecretReEncryptionJobBatchProcessor batchProcessor,
        SecretReEncryptionProperties properties
    ) {
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.jobService = Objects.requireNonNull(jobService, "jobService");
        this.batchProcessor = Objects.requireNonNull(batchProcessor, "batchProcessor");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void processDueJob() {
        LocalDateTime now = LocalDateTime.now();
        long tenantId = TenantContext.currentTenantIdOrDefault();
        SecretReEncryptionJob dueJob = jobMapper.selectDueJob(tenantId, now);
        if (dueJob == null) {
            return;
        }

        String ownerId = "repoguard-secret-re-encryption-" + UUID.randomUUID();
        LocalDateTime leaseUntil = now.plusSeconds(properties.getLeaseSeconds());
        if (jobMapper.claim(dueJob.getId(), tenantId, ownerId, now, leaseUntil) != 1) {
            return;
        }

        try {
            batchProcessor.process(dueJob.getId(), ownerId);
        } catch (SecretReEncryptionClaimLostException ex) {
            LOGGER.info(
                "Secret re-encryption batch stopped after claim changed jobId={} operation=secret_re_encryption result=claim_lost",
                dueJob.getId()
            );
        } catch (RuntimeException ex) {
            boolean recorded = jobService.markInfrastructureFailure(dueJob.getId(), ownerId, ex);
            LOGGER.warn(
                "Secret re-encryption batch failed jobId={} operation=secret_re_encryption result={} failureCategory={}",
                dueJob.getId(),
                recorded ? "retry_scheduled" : "claim_lost",
                ex.getClass().getSimpleName()
            );
        }
    }
}
