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
            sum(case when status = 'PUBLISH_FAILED' then 1 else 0 end) as publishFailed,
            sum(case when publish_claimed_at is not null then 1 else 0 end) as claimed,
            sum(case when status = 'DLQ' then 1 else 0 end) as dlqBacklog,
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
        where status in ('PUBLISH_FAILED', 'DLQ')
        order by created_at desc
        limit 20
        """)
    List<ReviewTask> selectMessageQueueExceptionTasks();

    interface MessageQueueHealthSummary {
        Long getTotal();

        Long getPublishFailed();

        Long getClaimed();

        Long getDlqBacklog();

        LocalDateTime getLatestFailureCreatedAt();
    }
}
