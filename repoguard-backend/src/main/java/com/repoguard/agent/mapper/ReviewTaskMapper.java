package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    @Insert("""
        insert ignore into review_task (
            pr_number, title, repository, organization, commit_sha, branch_name,
            status, risk_level, mq_retries, publish_attempts, llm_status, pr_url,
            source, trigger_source, human_review_required, human_review_status,
            created_at, duration_seconds
        ) values (
            #{prNumber}, #{title}, #{repository}, #{organization}, #{commitSha}, #{branchName},
            #{status}, #{riskLevel}, #{mqRetries}, #{publishAttempts}, #{llmStatus}, #{prUrl},
            #{source}, #{triggerSource}, #{humanReviewRequired}, #{humanReviewStatus},
            #{createdAt}, #{durationSeconds}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertManualReviewOrReuse(ReviewTask task);

    @Select("""
        select
            count(*) as total,
            sum(case
                when upper(coalesce(nullif(trim(status), ''), 'UNKNOWN')) = 'PUBLISH_FAILED'
                then 1 else 0 end) as publishFailed,
            sum(case
                when upper(coalesce(nullif(trim(status), ''), 'UNKNOWN')) = 'EXECUTION_TIMEOUT'
                then 1 else 0 end) as executionTimeout,
            sum(case
                when upper(coalesce(nullif(trim(status), ''), 'UNKNOWN')) = 'REQUEUE_PENDING'
                then 1 else 0 end) as requeuePending,
            sum(case when publish_claimed_at is not null then 1 else 0 end) as claimed,
            sum(case
                when upper(coalesce(nullif(trim(status), ''), 'UNKNOWN')) = 'DLQ'
                then 1 else 0 end) as dlqBacklog,
            max(case
                when last_publish_error is not null and last_publish_error <> ''
                then created_at
            end) as latestFailureCreatedAt
        from review_task
        """)
    MessageQueueHealthSummary selectMessageQueueHealthSummary();

    @Select("""
        select last_publish_error
        from review_task
        where last_publish_error is not null
          and last_publish_error <> ''
        order by created_at desc
        limit 1
        """)
    String selectLatestPublishFailureReason();

    @Select("""
        select *
        from review_task
        where upper(coalesce(nullif(trim(status), ''), 'UNKNOWN'))
            in ('PUBLISH_FAILED', 'EXECUTION_TIMEOUT', 'REQUEUE_PENDING', 'DLQ')
        order by created_at desc
        limit 20
        """)
    List<ReviewTask> selectMessageQueueExceptionTasks();

    @Select("""
        select distinct repository
        from review_task
        where repository is not null
          and trim(repository) <> ''
        order by repository asc
        """)
    List<String> selectDistinctRepositories();

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
