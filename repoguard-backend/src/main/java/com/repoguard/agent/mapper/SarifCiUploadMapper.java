package com.repoguard.agent.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Durable identity and completion metadata for the CI-native SARIF channel. */
@Mapper
public interface SarifCiUploadMapper {

    @Select("""
        select id, tenant_id as tenantId, task_id as taskId, attempt_id as attemptId,
               batch_id as batchId, tool_name as toolName, tool_version as toolVersion,
               scan_run_id as scanRunId, commit_sha as commitSha,
               sarif_fingerprint as sarifFingerprint, completion_time as completionTime,
               status, imported_count as importedCount, skipped_count as skippedCount,
               created_at as createdAt, updated_at as updatedAt
          from sarif_ci_upload
         where task_id = #{taskId}
           and attempt_id = #{attemptId}
           and tool_name = #{toolName}
           and tool_version = #{toolVersion}
           and commit_sha = #{commitSha}
           and scan_run_id = #{scanRunId}
         limit 1
        """)
    SarifCiUploadRow selectByIdentity(
        @Param("taskId") Long taskId,
        @Param("attemptId") Long attemptId,
        @Param("toolName") String toolName,
        @Param("toolVersion") String toolVersion,
        @Param("commitSha") String commitSha,
        @Param("scanRunId") String scanRunId
    );

    @Insert("""
        insert into sarif_ci_upload (
            tenant_id, task_id, attempt_id, batch_id, tool_name, tool_version,
            scan_run_id, commit_sha, sarif_fingerprint, completion_time, status,
            imported_count, skipped_count, created_at, updated_at
        ) values (
            #{upload.tenantId}, #{upload.taskId}, #{upload.attemptId}, #{upload.batchId},
            #{upload.toolName}, #{upload.toolVersion}, #{upload.scanRunId}, #{upload.commitSha},
            #{upload.sarifFingerprint}, #{upload.completionTime}, #{upload.status},
            #{upload.importedCount}, #{upload.skippedCount}, #{upload.createdAt}, #{upload.updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "upload.id")
    int insert(@Param("upload") SarifCiUploadRow upload);

    @Update("""
        update sarif_ci_upload
           set batch_id = #{upload.batchId}, sarif_fingerprint = #{upload.sarifFingerprint},
               completion_time = #{upload.completionTime}, status = 'ACTIVE',
               imported_count = #{upload.importedCount}, skipped_count = #{upload.skippedCount},
               updated_at = #{upload.updatedAt}
         where id = #{upload.id}
        """)
    int replace(@Param("upload") SarifCiUploadRow upload);

    class SarifCiUploadRow {
        private Long id;
        private Long tenantId;
        private Long taskId;
        private Long attemptId;
        private Long batchId;
        private String toolName;
        private String toolVersion;
        private String scanRunId;
        private String commitSha;
        private String sarifFingerprint;
        private LocalDateTime completionTime;
        private String status;
        private Integer importedCount;
        private Integer skippedCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public Long getAttemptId() { return attemptId; }
        public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
        public Long getBatchId() { return batchId; }
        public void setBatchId(Long batchId) { this.batchId = batchId; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getToolVersion() { return toolVersion; }
        public void setToolVersion(String toolVersion) { this.toolVersion = toolVersion; }
        public String getScanRunId() { return scanRunId; }
        public void setScanRunId(String scanRunId) { this.scanRunId = scanRunId; }
        public String getCommitSha() { return commitSha; }
        public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
        public String getSarifFingerprint() { return sarifFingerprint; }
        public void setSarifFingerprint(String sarifFingerprint) { this.sarifFingerprint = sarifFingerprint; }
        public LocalDateTime getCompletionTime() { return completionTime; }
        public void setCompletionTime(LocalDateTime completionTime) { this.completionTime = completionTime; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getImportedCount() { return importedCount; }
        public void setImportedCount(Integer importedCount) { this.importedCount = importedCount; }
        public Integer getSkippedCount() { return skippedCount; }
        public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
