package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.CiSarifSetupDto;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.SarifCiUploadMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CiSarifSetupService {
    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final SarifCiUploadMapper uploadMapper;

    public CiSarifSetupService(ReviewTaskMapper taskMapper, ReviewExecutionAttemptMapper attemptMapper,
        SarifCiUploadMapper uploadMapper) {
        this.taskMapper = taskMapper;
        this.attemptMapper = attemptMapper;
        this.uploadMapper = uploadMapper;
    }

    public CiSarifSetupDto get(Long taskId) {
        var task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        }
        ReviewExecutionAttempt attempt = task.getCurrentAttemptId() == null ? null
            : attemptMapper.selectById(task.getCurrentAttemptId());
        if (task.getCurrentAttemptId() != null && (attempt == null || !Objects.equals(taskId, attempt.getTaskId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "The current review attempt is missing or mismatched");
        }
        String commit = attempt != null && StringUtils.hasText(attempt.getCommitSha())
            ? attempt.getCommitSha() : task.getCommitSha();
        List<CiSarifSetupDto.Upload> uploads = attempt == null ? List.of()
            : uploadMapper.selectRecent(TenantContext.currentTenantIdOrDefault(), taskId, attempt.getId(), commit)
                .stream().map(row -> new CiSarifSetupDto.Upload(
                    row.getBatchId(), row.getToolName(), row.getToolVersion(), row.getScanRunId(),
                    row.getStatus(), row.getImportedCount(), row.getSkippedCount(),
                    row.getCompletionTime().atOffset(ZoneOffset.UTC).toString()
                )).toList();
        return new CiSarifSetupDto(taskId, task.getCurrentAttemptId(), task.getOrganization(), task.getRepository(),
            task.getPrNumber(), commit, CiSarifUploadCredentialService.TTL_SECONDS,
            CiSarifPayloadDecoder.MAX_UPLOAD_BYTES, CiSarifPayloadDecoder.MAX_SARIF_BYTES, "2.1.0", uploads);
    }
}
