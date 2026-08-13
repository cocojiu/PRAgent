package com.repoguard.agent.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.dto.SecretReEncryptionJobDto;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.entity.SecretReEncryptionJob;
import com.repoguard.agent.entity.SecretReEncryptionJobItem;
import com.repoguard.agent.mapper.SecretReEncryptionJobItemMapper;
import com.repoguard.agent.mapper.SecretReEncryptionJobMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SecretReEncryptionJobService {

    private static final String CONFIRM_TEXT = "RE-ENCRYPT";
    private static final String DEFAULT_SOURCE_KEY_ID = "local";
    private static final String INITIAL_TABLE = SecretReEncryptionJobTable.INTEGRATION_CONFIG.value();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SecretReEncryptionJobMapper jobMapper;
    private final SecretReEncryptionJobItemMapper itemMapper;
    private final SecretCryptoService activeCrypto;
    private final SecretReEncryptionProperties properties;

    public SecretReEncryptionJobService(
        SecretReEncryptionJobMapper jobMapper,
        SecretReEncryptionJobItemMapper itemMapper,
        SecretCryptoService activeCrypto,
        SecretReEncryptionProperties properties
    ) {
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper");
        this.activeCrypto = Objects.requireNonNull(activeCrypto, "activeCrypto");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Transactional
    public SecretReEncryptionJobDto start(
        SecretReEncryptionRequest request,
        Long operatorUserId,
        String operatorUsername
    ) {
        Objects.requireNonNull(request, "request");
        boolean execute = Boolean.TRUE.equals(request.execute());
        if (execute && !CONFIRM_TEXT.equals(request.confirmText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "confirmText must be RE-ENCRYPT when execute is true");
        }
        if (!StringUtils.hasText(request.sourceEncryptionKey())
            || !StringUtils.hasText(request.targetEncryptionKey())
            || !StringUtils.hasText(request.targetKeyId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "source and target encryption keys and target key id are required");
        }

        String sourceKeyId = StringUtils.hasText(request.sourceKeyId())
            ? request.sourceKeyId().trim()
            : DEFAULT_SOURCE_KEY_ID;
        String targetKeyId = request.targetKeyId().trim();
        SecretCryptoService sourceCrypto;
        SecretCryptoService targetCrypto;
        try {
            sourceCrypto = activeCrypto.migrationSource(request.sourceEncryptionKey(), sourceKeyId);
            targetCrypto = activeCrypto.migrationTarget(request.targetEncryptionKey(), targetKeyId);
        } catch (IllegalStateException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        if (sourceCrypto.activeKeyId().equals(targetCrypto.activeKeyId())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "targetKeyId must differ from sourceKeyId so target ciphertext can be identified safely"
            );
        }

        SecretReEncryptionJob job = new SecretReEncryptionJob();
        LocalDateTime now = LocalDateTime.now();
        job.setMode(execute ? SecretReEncryptionJobMode.EXECUTE.name() : SecretReEncryptionJobMode.DRY_RUN.name());
        job.setStatus(SecretReEncryptionJobStatus.PENDING.name());
        job.setSourceKeyId(sourceCrypto.activeKeyId());
        job.setTargetKeyId(targetCrypto.activeKeyId());
        // The submitted key material is encrypted with the currently active
        // application key. It is never returned by any API and survives a
        // worker restart as long as the active key is not switched mid-job.
        job.setSourceKeyCiphertext(activeCrypto.encrypt(request.sourceEncryptionKey()));
        job.setTargetKeyCiphertext(activeCrypto.encrypt(request.targetEncryptionKey()));
        job.setCurrentTable(INITIAL_TABLE);
        job.setCheckpointId(0L);
        job.setBatchSize(properties.getBatchSize());
        job.setScannedCount(0L);
        job.setReEncryptedCount(0L);
        job.setSkippedCount(0L);
        job.setFailedCount(0L);
        job.setRetryCount(0);
        job.setCreatedByUserId(operatorUserId);
        job.setCreatedByUsername(operatorUsername);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "A secret re-encryption job is already active or paused; wait for it to finish or resume it"
            );
        }
        return toDto(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean markInfrastructureFailure(Long jobId, String ownerId, RuntimeException failure) {
        SecretReEncryptionJob job = jobMapper.selectById(jobId);
        if (job == null
            || !SecretReEncryptionJobStatus.RUNNING.name().equals(job.getStatus())
            || !Objects.equals(ownerId, job.getClaimedBy())) {
            return false;
        }
        int retryCount = intValue(job.getRetryCount()) + 1;
        boolean terminal = retryCount >= properties.getMaxAttempts();
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SecretReEncryptionJob> update = new UpdateWrapper<SecretReEncryptionJob>()
            .eq("id", jobId)
            .eq("status", SecretReEncryptionJobStatus.RUNNING.name())
            .eq("claimed_by", ownerId)
            .set("status", terminal
                ? SecretReEncryptionJobStatus.FAILED.name()
                : SecretReEncryptionJobStatus.RETRY_WAIT.name())
            .set("retry_count", retryCount)
            .set("next_retry_at", terminal ? null : now.plusSeconds(properties.getRetryDelaySeconds()))
            .set("claimed_by", null)
            .set("claimed_at", null)
            .set("lease_until", null)
            .set("last_failure_reason", failure == null ? "batch_processing_failed" : failure.getClass().getSimpleName())
            .set("last_failure_message", "Secret re-encryption batch failed; inspect the job and retry it if needed")
            .set("updated_at", now);
        if (terminal) {
            update.set("completed_at", now);
        }
        return jobMapper.update(null, update) == 1;
    }

    public SecretReEncryptionJobDto get(Long jobId) {
        return toDto(requireJob(jobId));
    }

    public PageResponse<SecretReEncryptionJobDto> listJobs(int page, int pageSize) {
        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        Page<SecretReEncryptionJob> pageRequest = Page.of(normalizedPage, normalizedPageSize);
        Page<SecretReEncryptionJob> result = jobMapper.selectPage(
            pageRequest,
            new QueryWrapper<SecretReEncryptionJob>().orderByDesc("id")
        );
        return new PageResponse<>(result.getRecords().stream().map(this::toDto).toList(), result.getTotal());
    }

    public PageResponse<SecretReEncryptionItemDto> listItems(Long jobId, int page, int pageSize) {
        requireJob(jobId);
        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        Page<SecretReEncryptionJobItem> pageRequest = Page.of(normalizedPage, normalizedPageSize);
        Page<SecretReEncryptionJobItem> result = itemMapper.selectPage(
            pageRequest,
            new QueryWrapper<SecretReEncryptionJobItem>()
                .eq("job_id", jobId)
                .orderByAsc("id")
        );
        List<SecretReEncryptionItemDto> items = result.getRecords().stream().map(this::toItemDto).toList();
        return new PageResponse<>(items, result.getTotal());
    }

    @Transactional
    public SecretReEncryptionJobDto pause(Long jobId) {
        requireJob(jobId);
        LocalDateTime now = LocalDateTime.now();
        int updated = jobMapper.update(
            null,
            new UpdateWrapper<SecretReEncryptionJob>()
                .eq("id", jobId)
                .in(
                    "status",
                    SecretReEncryptionJobStatus.PENDING.name(),
                    SecretReEncryptionJobStatus.RETRY_WAIT.name(),
                    SecretReEncryptionJobStatus.RUNNING.name()
                )
                .set("status", SecretReEncryptionJobStatus.PAUSED.name())
                .set("next_retry_at", null)
                .set("claimed_by", null)
                .set("claimed_at", null)
                .set("lease_until", null)
                .set("updated_at", now)
        );
        if (updated != 1) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Only a pending, retry-waiting, or running job can be paused"
            );
        }
        return toDto(requireJob(jobId));
    }

    @Transactional
    public SecretReEncryptionJobDto resume(Long jobId) {
        requireJob(jobId);
        LocalDateTime now = LocalDateTime.now();
        try {
            int updated = jobMapper.update(
                null,
                new UpdateWrapper<SecretReEncryptionJob>()
                    .eq("id", jobId)
                    .in(
                        "status",
                        SecretReEncryptionJobStatus.PAUSED.name(),
                        SecretReEncryptionJobStatus.FAILED.name()
                    )
                    .set("status", SecretReEncryptionJobStatus.PENDING.name())
                    .set("retry_count", 0)
                    .set("next_retry_at", null)
                    .set("claimed_by", null)
                    .set("claimed_at", null)
                    .set("lease_until", null)
                    .set("last_failure_reason", null)
                    .set("last_failure_message", null)
                    .set("completed_at", null)
                    .set("updated_at", now)
            );
            if (updated != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "Only a paused or failed job can be resumed");
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Another secret re-encryption job is already active or paused"
            );
        }
        return toDto(requireJob(jobId));
    }

    SecretReEncryptionJob requireJob(Long jobId) {
        SecretReEncryptionJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Secret re-encryption job not found: " + jobId);
        }
        return job;
    }

    private SecretReEncryptionItemDto toItemDto(SecretReEncryptionJobItem item) {
        return new SecretReEncryptionItemDto(
            item.getTableName(),
            item.getRecordId(),
            item.getFieldName(),
            item.getProvider(),
            item.getSourceFormat(),
            item.getSourceKeyId(),
            item.getTargetKeyId(),
            item.getStatus(),
            item.getFailureReason(),
            item.getMessage()
        );
    }

    SecretReEncryptionJobDto toDto(SecretReEncryptionJob job) {
        return new SecretReEncryptionJobDto(
            job.getId(),
            SecretReEncryptionJobMode.EXECUTE.name().equals(job.getMode()),
            job.getStatus(),
            job.getSourceKeyId(),
            job.getTargetKeyId(),
            job.getCurrentTable(),
            longValue(job.getCheckpointId()),
            intValue(job.getBatchSize()),
            longValue(job.getScannedCount()),
            longValue(job.getReEncryptedCount()),
            longValue(job.getSkippedCount()),
            longValue(job.getFailedCount()),
            intValue(job.getRetryCount()),
            format(job.getNextRetryAt()),
            job.getLastFailureReason(),
            job.getCreatedByUsername(),
            format(job.getCreatedAt()),
            format(job.getUpdatedAt()),
            format(job.getCompletedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME_FORMATTER.format(value);
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
