package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.GithubCommentPreviewFindingStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.ReviewTaskDetailSummary;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleFeedbackStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleHitCount;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.SeverityCounts;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReviewFindingMapper extends BaseMapper<ReviewFinding> {

    @Select("""
        select *
        from review_finding
        where task_id = #{taskId}
          and category = 'FINDING'
        order by attempt_id asc, id asc
        """)
    List<ReviewFinding> selectByTaskIdForComparison(@Param("taskId") Long taskId);

    @Select("""
        select finding.*
          from review_finding finding
          join review_task task on task.id = finding.task_id
         where lower(task.organization) = lower(#{organization})
           and lower(task.repository) = lower(#{repository})
           and finding.category = 'FINDING'
           and upper(finding.rule_id) = upper(#{ruleId})
           and finding.current_attempt = 1
         order by finding.id desc
         limit #{limit}
        """)
    List<ReviewFinding> selectRecentSuppressionHits(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("ruleId") String ruleId,
        @Param("limit") int limit
    );

    @Update("""
        update review_finding
        set finding_fingerprint = #{findingFingerprint},
            previous_finding_id = #{previousFindingId},
            comparison_status = #{comparisonStatus},
            comparison_confidence = #{comparisonConfidence},
            comparison_reason = #{comparisonReason},
            comparison_version = #{comparisonVersion},
            comparison_attempt_id = #{comparisonAttemptId}
        where id = #{findingId}
          and task_id = #{taskId}
          and attempt_id = #{attemptId}
        """)
    int updateComparison(
        @Param("findingId") Long findingId,
        @Param("taskId") Long taskId,
        @Param("attemptId") Long attemptId,
        @Param("findingFingerprint") String findingFingerprint,
        @Param("previousFindingId") Long previousFindingId,
        @Param("comparisonStatus") String comparisonStatus,
        @Param("comparisonConfidence") java.math.BigDecimal comparisonConfidence,
        @Param("comparisonReason") String comparisonReason,
        @Param("comparisonVersion") String comparisonVersion,
        @Param("comparisonAttemptId") Long comparisonAttemptId
    );

    @Update("""
        update review_finding
        set current_attempt = 0
        where task_id = #{taskId}
          and current_attempt = 1
        """)
    int markCurrentAttemptHistorical(Long taskId);

    @Select("""
        select rule_id as ruleId, count(*) as total
        from review_finding
        where category = 'FINDING'
          and current_attempt = 1
          and rule_id is not null
          and trim(rule_id) <> ''
        group by rule_id
        """)
    List<RuleHitCount> selectReviewRuleHitCounts();

    @Select("""
        select
            count(*) as totalHits,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as validCount,
            sum(case
                when feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as reviewedCount
        from review_finding
        where category = 'FINDING'
          and current_attempt = 1
        """)
    RuleFeedbackStat selectReviewRuleFeedbackStat();

    @Select("""
        select
            sum(case
                when severity_norm = 'critical'
                then 1 else 0 end) as critical,
            sum(case
                when severity_norm = 'high'
                then 1 else 0 end) as high,
            sum(case
                when severity_norm = 'medium'
                then 1 else 0 end) as medium,
            sum(case
                when severity_norm = 'low'
                then 1 else 0 end) as low,
            sum(case
                when severity_norm not in ('critical', 'high', 'medium', 'low')
                then 1 else 0 end) as info
        from review_finding
        where task_id = #{taskId}
          and current_attempt = 1
          and category = 'FINDING'
          and feedback_status_norm <> 'FALSE_POSITIVE'
          and enforcement_mode <> 'OBSERVE'
        """)
    SeverityCounts selectFindingSeverityCounts(Long taskId);

    @Select("""
        select
            (
                select count(*)
                from changed_file changed
                where changed.task_id = #{taskId}
                  and changed.current_attempt = 1
            ) as changedFileTotal,
            sum(case
                when finding.category = 'FINDING'
                then 1 else 0 end) as findingTotal,
            sum(case
                when finding.category = 'MISSING_TEST'
                then 1 else 0 end) as missingTestTotal,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'critical'
                then 1 else 0 end) as critical,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'high'
                then 1 else 0 end) as high,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'medium'
                then 1 else 0 end) as medium,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'low'
                then 1 else 0 end) as low,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm not in ('critical', 'high', 'medium', 'low')
                then 1 else 0 end) as info
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
        """)
    ReviewTaskDetailSummary selectReviewTaskDetailSummary(Long taskId);

    @Select("""
        select
            count(*) as totalFindings,
            sum(case
                when exists (
                    select 1
                    from github_comment_publication publication
                    where publication.task_id = finding.task_id
                      and publication.finding_id = finding.id
                      and publication.published_success = 1
                )
                then 1 else 0 end
            ) as publishedFindings,
            sum(case
                when not exists (
                    select 1
                    from github_comment_publication publication
                    where publication.task_id = finding.task_id
                      and publication.finding_id = finding.id
                      and publication.published_success = 1
                )
                and (
                    finding.feedback_status_norm in ('UNREVIEWED', 'VALID')
                )
                and finding.enforcement_mode <> 'OBSERVE'
                then 1 else 0 end
            ) as commentableFindings
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
        """)
    GithubCommentPreviewFindingStat selectGithubCommentPreviewFindingStat(Long taskId);

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
        order by finding.id asc
        limit #{limit} offset #{offset}
        """)
    List<ReviewFinding> selectGithubCommentPreviewFindings(
        @Param("taskId") Long taskId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
          and not exists (
              select 1
              from github_comment_publication publication
              where publication.task_id = finding.task_id
                and publication.finding_id = finding.id
                and publication.published_success = 1
          )
          and (
              finding.feedback_status_norm in ('UNREVIEWED', 'VALID')
          )
          and finding.enforcement_mode <> 'OBSERVE'
        order by finding.id asc
        limit #{limit} offset #{offset}
        """)
    List<ReviewFinding> selectGithubCommentPreviewCommentableFindings(
        @Param("taskId") Long taskId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
          and finding.id > #{afterFindingId}
          and not exists (
              select 1
              from github_comment_publication publication
              where publication.task_id = finding.task_id
                and publication.finding_id = finding.id
                and publication.published_success = 1
          )
          and (
              finding.feedback_status_norm in ('UNREVIEWED', 'VALID')
          )
          and upper(coalesce(finding.comparison_status, 'UNMATCHED')) in ('NEW', 'REGRESSED')
          and finding.enforcement_mode <> 'OBSERVE'
        order by finding.id asc
        limit #{limit}
        """)
    List<ReviewFinding> selectGithubCommentPublishCandidatesAfterId(
        @Param("taskId") Long taskId,
        @Param("afterFindingId") long afterFindingId,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding
        where task_id = #{taskId}
          and current_attempt = 1
          and category = 'FINDING'
          and coalesce(feedback_status_norm, 'UNREVIEWED') <> 'FALSE_POSITIVE'
          and is_blocking = 1
          and upper(enforcement_mode) = 'BLOCK'
        order by id asc
        """)
    List<ReviewFinding> selectGithubCheckRunBlockingFindings(@Param("taskId") Long taskId);

    @Select("""
        select id, task_id as taskId, attempt_id as attemptId, tool_name as toolName,
               tool_version as toolVersion, commit_sha as commitSha,
               content_fingerprint as contentFingerprint, status,
               imported_count as importedCount, skipped_count as skippedCount
        from sarif_import_batch
        where task_id = #{taskId}
          and attempt_id = #{attemptId}
          and tool_name = #{toolName}
          and tool_version = #{toolVersion}
          and commit_sha = #{commitSha}
          and content_fingerprint = #{fingerprint}
        order by id desc
        limit 1
        """)
    SarifImportBatchRow selectSarifImportBatch(
        @Param("taskId") Long taskId,
        @Param("attemptId") Long attemptId,
        @Param("toolName") String toolName,
        @Param("toolVersion") String toolVersion,
        @Param("commitSha") String commitSha,
        @Param("fingerprint") String fingerprint
    );

    @Insert("""
        insert into sarif_import_batch (
            tenant_id, task_id, attempt_id, tool_name, tool_version, commit_sha,
            content_fingerprint, status, imported_count, skipped_count, created_at, updated_at
        ) values (
            #{batch.tenantId}, #{batch.taskId}, #{batch.attemptId}, #{batch.toolName}, #{batch.toolVersion},
            #{batch.commitSha}, #{batch.contentFingerprint}, #{batch.status}, #{batch.importedCount},
            #{batch.skippedCount}, #{batch.createdAt}, #{batch.updatedAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "batch.id")
    int insertSarifImportBatch(@Param("batch") SarifImportBatchRow batch);

    @Select("""
        select id, task_id as taskId, attempt_id as attemptId, tool_name as toolName,
               tool_version as toolVersion, commit_sha as commitSha,
               content_fingerprint as contentFingerprint, status,
               imported_count as importedCount, skipped_count as skippedCount
        from sarif_import_batch
        where task_id = #{taskId}
          and attempt_id = #{attemptId}
          and tool_name = #{toolName}
          and tool_version = #{toolVersion}
          and commit_sha = #{commitSha}
          and status = 'ACTIVE'
          and content_fingerprint <> #{fingerprint}
        order by id
        """)
    List<SarifImportBatchRow> selectActiveSarifImportBatches(
        @Param("taskId") Long taskId,
        @Param("attemptId") Long attemptId,
        @Param("toolName") String toolName,
        @Param("toolVersion") String toolVersion,
        @Param("commitSha") String commitSha,
        @Param("fingerprint") String fingerprint
    );

    @Update("""
        update sarif_import_batch
        set status = 'SUPERSEDED', updated_at = #{updatedAt}
        where id = #{batchId}
        """)
    int markSarifImportBatchSuperseded(
        @Param("batchId") Long batchId,
        @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    /** Minimal MyBatis row model kept beside the owning finding mapper. */
    class SarifImportBatchRow {
        private Long id;
        private Long tenantId;
        private Long taskId;
        private Long attemptId;
        private String toolName;
        private String toolVersion;
        private String commitSha;
        private String contentFingerprint;
        private String status;
        private Integer importedCount;
        private Integer skippedCount;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public Long getAttemptId() { return attemptId; }
        public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getToolVersion() { return toolVersion; }
        public void setToolVersion(String toolVersion) { this.toolVersion = toolVersion; }
        public String getCommitSha() { return commitSha; }
        public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
        public String getContentFingerprint() { return contentFingerprint; }
        public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getImportedCount() { return importedCount; }
        public void setImportedCount(Integer importedCount) { this.importedCount = importedCount; }
        public Integer getSkippedCount() { return skippedCount; }
        public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
