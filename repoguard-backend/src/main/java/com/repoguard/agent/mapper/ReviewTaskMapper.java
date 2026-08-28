package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.repoguard.agent.entity.ReviewTask;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    @Insert("""
        insert into review_task (
            pr_number, title, repository, organization, commit_sha, generation, branch_name,
            status, risk_level, assessment_status, mq_retries, publish_attempts, llm_status, pr_url,
            source, trigger_source, human_review_required, human_review_status,
            created_at, finished_at, duration_seconds, llm_fallback_reason
        ) values (
            #{prNumber}, #{title}, #{repository}, #{organization}, #{commitSha}, #{generation}, #{branchName},
            #{status}, #{riskLevel}, #{assessmentStatus}, #{mqRetries}, #{publishAttempts}, #{llmStatus}, #{prUrl},
            #{source}, #{triggerSource}, #{humanReviewRequired}, #{humanReviewStatus},
            #{createdAt}, #{finishedAt}, #{durationSeconds}, #{llmFallbackReason}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertManualReview(ReviewTask task);

    @Update("""
        update review_task
        set status = 'SUPERSEDED',
            risk_level = 'INFO',
            assessment_status = 'SUPERSEDED',
            llm_status = 'pending',
            llm_fallback_reason = #{reason},
            finished_at = #{supersededAt},
            duration_seconds = greatest(
                timestampdiff(second, coalesce(started_at, created_at), #{supersededAt}),
                0
            ),
            publish_claimed_at = null,
            publish_claimed_by = null,
            review_claimed_at = null,
            review_claimed_by = null
        where organization = #{organization}
          and repository = #{repository}
          and pr_number = #{prNumber}
          and generation < #{generation}
          and upper(coalesce(nullif(trigger_source, ''), source, '')) = 'GITHUB_WEBHOOK'
          and status in ('QUEUED', 'PUBLISH_FAILED', 'REQUEUE_PENDING')
        """)
    int supersedeOlderPendingTasks(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber,
        @Param("generation") Long generation,
        @Param("supersededAt") LocalDateTime supersededAt,
        @Param("reason") String reason
    );

    @Select("""
        select id
        from review_task
        where organization = #{organization}
          and repository = #{repository}
          and pr_number = #{prNumber}
          and generation < #{generation}
          and upper(coalesce(nullif(trigger_source, ''), source, '')) = 'GITHUB_WEBHOOK'
          and status = 'SUPERSEDED'
          and finished_at = #{supersededAt}
        order by id
        """)
    List<Long> selectTasksSupersededAtGeneration(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber,
        @Param("generation") Long generation,
        @Param("supersededAt") LocalDateTime supersededAt
    );

    @Update("""
        update review_task
        set generation = #{generation},
            status = 'PUBLISH_FAILED',
            risk_level = 'INFO',
            assessment_status = 'PARTIAL',
            llm_status = 'pending',
            llm_fallback_reason = null,
            finished_at = null,
            duration_seconds = 0,
            next_publish_retry_at = #{recoveredAt},
            last_publish_error = 'Pull request head mismatch recovery requires republish',
            publish_claimed_at = null,
            publish_claimed_by = null,
            review_claimed_at = null,
            review_claimed_by = null
        where organization = #{organization}
          and repository = #{repository}
          and pr_number = #{prNumber}
          and commit_sha = #{commitSha}
          and upper(coalesce(nullif(trigger_source, ''), source, '')) = 'GITHUB_WEBHOOK'
          and status in ('QUEUED', 'PUBLISH_FAILED', 'REQUEUE_PENDING', 'SUPERSEDED')
        """)
    int prepareCurrentHeadTaskForRepublish(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber,
        @Param("commitSha") String commitSha,
        @Param("generation") Long generation,
        @Param("recoveredAt") LocalDateTime recoveredAt
    );

    @Update("""
        update review_task task
        set task.status = 'REVIEWING',
            task.started_at = #{startedAt},
            task.review_claimed_at = #{startedAt},
            task.review_claimed_by = #{claimId},
            task.publish_claimed_at = null,
            task.publish_claimed_by = null
        where task.id = #{taskId}
          and task.status = 'QUEUED'
          and (
              upper(coalesce(nullif(task.trigger_source, ''), task.source, '')) <> 'GITHUB_WEBHOOK'
              or
              not exists (
                  select 1
                  from review_pull_request_head head
                  where head.organization = task.organization
                    and head.repository = task.repository
                    and head.pr_number = task.pr_number
              )
              or exists (
                  select 1
                  from review_pull_request_head head
                  where head.organization = task.organization
                    and head.repository = task.repository
                    and head.pr_number = task.pr_number
                    and head.generation = task.generation
                    and head.latest_commit_sha = task.commit_sha
              )
          )
        """)
    int claimCurrentReview(
        @Param("taskId") Long taskId,
        @Param("startedAt") LocalDateTime startedAt,
        @Param("claimId") String claimId
    );

    @Update("""
        update review_task
        set current_attempt_id = #{attemptId}
        where id = #{taskId}
          and status = 'REVIEWING'
          and review_claimed_by = #{claimId}
        """)
    int attachCurrentAttempt(
        @Param("taskId") Long taskId,
        @Param("claimId") String claimId,
        @Param("attemptId") Long attemptId
    );

    @Select("""
        select
            count(*) as total,
            sum(case
                when status_norm = 'PUBLISH_FAILED'
                then 1 else 0 end) as publishFailed,
            sum(case
                when status_norm = 'EXECUTION_TIMEOUT'
                then 1 else 0 end) as executionTimeout,
            sum(case
                when status_norm = 'REQUEUE_PENDING'
                then 1 else 0 end) as requeuePending,
            sum(case when publish_claimed_at is not null then 1 else 0 end) as claimed,
            sum(case
                when status_norm = 'DLQ'
                then 1 else 0 end) as dlqBacklog,
            max(case
                when last_publish_error is not null and last_publish_error <> ''
                then created_at
            end) as latestFailureCreatedAt
        from review_task
        where created_at >= #{createdAfter}
        """)
    MessageQueueHealthSummary selectMessageQueueHealthSummary(@Param("createdAfter") LocalDateTime createdAfter);

    @Select("""
        select
            count(*) as total,
            sum(case
                when assessment_status = 'COMPLETE'
                  and risk_level_norm in ('HIGH', 'CRITICAL')
                then 1 else 0 end) as highRisk,
            sum(case
                when status_norm = 'FAILED'
                then 1 else 0 end) as failed,
            avg(case when finished_at is not null then duration_seconds end) as averageDurationSeconds
        from review_task
        ${ew.customSqlSegment}
        """)
    ReviewTaskListSummaryStat selectListSummaryStat(@Param(Constants.WRAPPER) Wrapper<ReviewTask> wrapper);

    @Select("""
        select last_publish_error
        from review_task
        where created_at >= #{createdAfter}
          and last_publish_error is not null
          and last_publish_error <> ''
        order by created_at desc
        limit 1
        """)
    String selectLatestPublishFailureReason(@Param("createdAfter") LocalDateTime createdAfter);

    @Select("""
        select *
        from review_task
        where status_norm in ('PUBLISH_FAILED', 'EXECUTION_TIMEOUT', 'REQUEUE_PENDING', 'DLQ')
        order by created_at desc
        limit 20
        """)
    List<ReviewTask> selectMessageQueueExceptionTasks();

    class ReviewTaskListSummaryStat {
        private Long total;
        private Long highRisk;
        private Long failed;
        private BigDecimal averageDurationSeconds;

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getHighRisk() {
            return highRisk;
        }

        public void setHighRisk(Long highRisk) {
            this.highRisk = highRisk;
        }

        public Long getFailed() {
            return failed;
        }

        public void setFailed(Long failed) {
            this.failed = failed;
        }

        public BigDecimal getAverageDurationSeconds() {
            return averageDurationSeconds;
        }

        public void setAverageDurationSeconds(BigDecimal averageDurationSeconds) {
            this.averageDurationSeconds = averageDurationSeconds;
        }
    }

    class MessageQueueHealthSummary {
        private Long total;
        private Long publishFailed;
        private Long executionTimeout;
        private Long requeuePending;
        private Long claimed;
        private Long dlqBacklog;
        private LocalDateTime latestFailureCreatedAt;

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getPublishFailed() {
            return publishFailed;
        }

        public void setPublishFailed(Long publishFailed) {
            this.publishFailed = publishFailed;
        }

        public Long getExecutionTimeout() {
            return executionTimeout;
        }

        public void setExecutionTimeout(Long executionTimeout) {
            this.executionTimeout = executionTimeout;
        }

        public Long getRequeuePending() {
            return requeuePending;
        }

        public void setRequeuePending(Long requeuePending) {
            this.requeuePending = requeuePending;
        }

        public Long getClaimed() {
            return claimed;
        }

        public void setClaimed(Long claimed) {
            this.claimed = claimed;
        }

        public Long getDlqBacklog() {
            return dlqBacklog;
        }

        public void setDlqBacklog(Long dlqBacklog) {
            this.dlqBacklog = dlqBacklog;
        }

        public LocalDateTime getLatestFailureCreatedAt() {
            return latestFailureCreatedAt;
        }

        public void setLatestFailureCreatedAt(LocalDateTime latestFailureCreatedAt) {
            this.latestFailureCreatedAt = latestFailureCreatedAt;
        }
    }
}
