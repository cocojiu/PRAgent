package com.repoguard.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.SarifCiUploadMapper;
import com.repoguard.agent.mapper.SarifCiUploadMapper.SarifCiUploadRow;
import com.repoguard.agent.tenancy.TenantContext;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CiSarifUploadServiceTest {

    private final CiSarifUploadCredentialService credentialService = org.mockito.Mockito.mock(CiSarifUploadCredentialService.class);
    private final CiSarifPayloadDecoder payloadDecoder = org.mockito.Mockito.mock(CiSarifPayloadDecoder.class);
    private final SarifFindingService sarifFindingService = org.mockito.Mockito.mock(SarifFindingService.class);
    private final SarifCiUploadMapper uploadMapper = org.mockito.Mockito.mock(SarifCiUploadMapper.class);
    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(ReviewExecutionAttemptMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final CiSarifUploadService service = new CiSarifUploadService(
        credentialService, payloadDecoder, sarifFindingService, uploadMapper,
        taskMapper, attemptMapper, findingMapper
    );
    private final CiSarifUploadCredentialService.Claims claims = new CiSarifUploadCredentialService.Claims(
        7L, 9L, 17L, 42, "org", "repo", "abc123", 1_725_000_000L, 1_725_000_600L
    );

    @BeforeEach
    void defaults() {
        when(credentialService.verify("credential")).thenReturn(claims);
        when(taskMapper.selectById(9L)).thenReturn(task());
        when(attemptMapper.selectById(17L)).thenReturn(attempt());
        when(payloadDecoder.decode(any(InputStream.class), anyLong(), eq("application/json"))).thenReturn("sarif-content");
        when(sarifFindingService.contentFingerprint("sarif-content")).thenReturn("f".repeat(64));
        when(sarifFindingService.importFindings(any(), any())).thenReturn(new SarifImportResponse(9L, 2, 1, List.of()));
        SarifImportBatchRow batch = new SarifImportBatchRow();
        batch.setId(101L);
        batch.setToolName("codeql");
        batch.setToolVersion("2.1");
        when(findingMapper.selectSarifImportBatchByFingerprint(
            any(), any(), any(), any(), any(), any()
        )).thenReturn(batch);
    }

    @Test
    void importsAndPersistsOneBoundedCiRun() {
        try (TenantContext.Scope _ = TenantContext.withTenant(1L)) {
            var result = service.upload(
                9L, "credential", "codeql", "2.1", "run-17", "abc123",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                "application/json", "payload".getBytes()
            );
            assertThat(result.taskId()).isEqualTo(9L);
            assertThat(result.toolName()).isEqualTo("codeql");
            assertThat(result.imported()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(1);
            verify(uploadMapper).insert(any(SarifCiUploadRow.class));
        }
    }

    @Test
    void retriesSameScanRunIdempotentlyWithoutReimporting() {
        SarifCiUploadRow existing = existing("f".repeat(64));
        when(uploadMapper.selectByIdentity(any(), any(), any(), any(), any(), any())).thenReturn(existing);
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            var result = service.upload(
                9L, "credential", "codeql", "2.1", "run-17", "abc123",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                "application/json", "payload".getBytes()
            );
            assertThat(result.status()).isEqualTo("ACTIVE");
            verify(sarifFindingService, never()).importFindings(any(), any());
            verify(uploadMapper, never()).replace(any());
        }
    }

    @Test
    void replacesChangedPayloadForSameScanRunAndRejectsMismatches() {
        SarifCiUploadRow existing = existing("old");
        when(uploadMapper.selectByIdentity(any(), any(), any(), any(), any(), any())).thenReturn(existing);
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            service.upload(
                9L, "credential", "codeql", "2.1", "run-17", "abc123",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                "application/json", "payload".getBytes()
            );
            verify(uploadMapper).replace(existing);
            assertThat(existing.getBatchId()).isEqualTo(101L);
        }
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "other",
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
            "application/json", "payload".getBytes()
        )).isInstanceOf(BusinessException.class).hasMessageContaining("does not match");
    }

    @Test
    void rejectsFutureCompletionAndInvalidScanRun() {
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "bad run", "abc123",
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("Scan-Run is invalid");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-18", "abc123",
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).toString(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("future");
    }

    @Test
    void rejectsInvalidIdentityHeadersAndCompletionBeforeImport() {
        assertThatThrownBy(() -> service.upload(
            null, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("not bound to this task");
        assertThatThrownBy(() -> service.upload(
            8L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("not bound to this task");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", " ", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("X-RepoGuard-CI-Tool is invalid");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "to\u0001ol", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("X-RepoGuard-CI-Tool is invalid");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "x".repeat(129), "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("X-RepoGuard-CI-Tool is invalid");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", null, past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("X-RepoGuard-CI-Commit-SHA is invalid");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc 123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("does not match");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", null,
            "application/json", "payload".getBytes()
        )).hasMessageContaining("Completed-At is required");
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", "not-rfc3339",
            "application/json", "payload".getBytes()
        )).hasMessageContaining("must be RFC3339");
    }

    @Test
    void rejectsMissingAttemptAndMismatchedTaskMetadata() {
        when(taskMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("Review task not found");

        ReviewTask notCurrent = task();
        notCurrent.setCurrentAttemptId(18L);
        assertThatThrownBy(uploadWithTask(notCurrent))
            .hasMessageContaining("current review attempt");

        when(taskMapper.selectById(9L)).thenReturn(task());
        when(attemptMapper.selectById(17L)).thenReturn(null);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("missing or mismatched");

        when(attemptMapper.selectById(17L)).thenReturn(attempt());
        ReviewTask wrongOrganization = task();
        wrongOrganization.setOrganization("other-org");
        when(taskMapper.selectById(9L)).thenReturn(wrongOrganization);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("no longer matches");

        ReviewTask wrongRepository = task();
        wrongRepository.setRepository("other-repo");
        when(taskMapper.selectById(9L)).thenReturn(wrongRepository);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("no longer matches");

        ReviewTask wrongPr = task();
        wrongPr.setPrNumber(99);
        when(taskMapper.selectById(9L)).thenReturn(wrongPr);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("no longer matches");
    }

    @Test
    void handlesOptionalMetadataMissingBatchesAndInsertRaces() {
        SarifImportBatchRow blankVersion = batchRow(101L, "codeql", "");
        when(findingMapper.selectSarifImportBatchByFingerprint(any(), any(), any(), any(), any(), any()))
            .thenReturn(blankVersion);
        var blankVersionResult = service.upload(
            9L, "credential", " codeql ", " ", "run-19", "abc123", past(),
            "application/json", "payload".getBytes()
        );
        assertThat(blankVersionResult.toolVersion()).isEmpty();

        when(findingMapper.selectSarifImportBatchByFingerprint(any(), any(), any(), any(), any(), any()))
            .thenReturn(null);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-20", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("batch was not created");

        SarifImportBatchRow wrongTool = batchRow(102L, "semgrep", "2.1");
        when(findingMapper.selectSarifImportBatchByFingerprint(any(), any(), any(), any(), any(), any()))
            .thenReturn(wrongTool);
        assertThatThrownBy(() -> service.upload(
            9L, "credential", "codeql", "2.1", "run-21", "abc123", past(),
            "application/json", "payload".getBytes()
        )).hasMessageContaining("tool metadata");

        when(findingMapper.selectSarifImportBatchByFingerprint(any(), any(), any(), any(), any(), any()))
            .thenReturn(batchRow(103L, "codeql", "2.1"));
        when(uploadMapper.selectByIdentity(any(), any(), any(), any(), any(), any()))
            .thenReturn(null, existing("f".repeat(64)));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("race"))
            .when(uploadMapper).insert(any(SarifCiUploadRow.class));
        var raced = service.upload(
            9L, "credential", "codeql", "2.1", "run-22", "abc123", past(),
            "application/json", "payload".getBytes()
        );
        assertThat(raced.sarifFingerprint()).isEqualTo("f".repeat(64));
    }

    @Test
    void mapsNullableIdempotentResponseFields() {
        SarifCiUploadRow existing = new SarifCiUploadRow();
        existing.setTaskId(9L);
        existing.setAttemptId(17L);
        existing.setToolName("codeql");
        existing.setToolVersion("2.1");
        existing.setScanRunId("run-null");
        existing.setCommitSha("abc123");
        existing.setSarifFingerprint("f".repeat(64));
        existing.setStatus("ACTIVE");
        when(uploadMapper.selectByIdentity(any(), any(), any(), any(), any(), any())).thenReturn(existing);
        var result = service.upload(
            9L, "credential", "codeql", "2.1", "run-null", "abc123", past(),
            "application/json", "payload".getBytes()
        );
        assertThat(result.completedAt()).isNull();
        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isZero();
    }

    private String past() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString();
    }

    private SarifImportBatchRow batchRow(long id, String tool, String version) {
        SarifImportBatchRow row = new SarifImportBatchRow();
        row.setId(id);
        row.setToolName(tool);
        row.setToolVersion(version);
        return row;
    }

    private org.assertj.core.api.ThrowableAssert.ThrowingCallable uploadWithTask(ReviewTask task) {
        when(taskMapper.selectById(9L)).thenReturn(task);
        return () -> service.upload(
            9L, "credential", "codeql", "2.1", "run-current", "abc123", past(),
            "application/json", "payload".getBytes()
        );
    }

    private SarifCiUploadRow existing(String fingerprint) {
        SarifCiUploadRow row = new SarifCiUploadRow();
        row.setId(44L);
        row.setTaskId(9L);
        row.setAttemptId(17L);
        row.setToolName("codeql");
        row.setToolVersion("2.1");
        row.setScanRunId("run-17");
        row.setCommitSha("abc123");
        row.setSarifFingerprint(fingerprint);
        row.setStatus("ACTIVE");
        row.setImportedCount(2);
        row.setSkippedCount(1);
        row.setCompletionTime(java.time.LocalDateTime.now(ZoneOffset.UTC));
        return row;
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setCurrentAttemptId(17L);
        task.setOrganization("org");
        task.setRepository("repo");
        task.setPrNumber(42);
        task.setCommitSha("abc123");
        return task;
    }

    private ReviewExecutionAttempt attempt() {
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setId(17L);
        attempt.setTaskId(9L);
        attempt.setCommitSha("abc123");
        return attempt;
    }
}
