package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReviewExecutionAttemptMapper extends BaseMapper<ReviewExecutionAttempt> {

    @Select("""
        select coalesce(max(attempt_no), 0)
        from review_execution_attempt
        where task_id = #{taskId}
        """)
    int selectLatestAttemptNo(Long taskId);

    @Select("""
        select *
        from review_execution_attempt
        where task_id = #{taskId}
        order by attempt_no desc
        limit #{limit}
        """)
    List<ReviewExecutionAttempt> selectByTaskId(
        @Param("taskId") Long taskId,
        @Param("limit") int limit
    );

    @Update("""
        update review_execution_attempt
        set status = 'ABANDONED',
            failure_category = #{failureCategory},
            finished_at = #{finishedAt},
            total_ms = greatest(timestampdiff(microsecond, started_at, #{finishedAt}) div 1000, 0)
        where id = #{attemptId}
          and task_id = #{taskId}
          and claim_id = #{claimId}
          and status = 'RUNNING'
        """)
    int abandonRunningAttempt(
        @Param("attemptId") Long attemptId,
        @Param("taskId") Long taskId,
        @Param("claimId") String claimId,
        @Param("failureCategory") String failureCategory,
        @Param("finishedAt") java.time.LocalDateTime finishedAt
    );

    @Select("""
        select attempt.id
        from review_execution_attempt attempt
        join review_task task on task.id = attempt.task_id
        where attempt.finished_at < #{cutoff}
          and attempt.payload_purged_at is null
          and attempt.status <> 'RUNNING'
          and (task.current_attempt_id is null or task.current_attempt_id <> attempt.id)
          and not exists (
              select 1
              from github_comment_publication publication
              join review_finding finding on finding.id = publication.finding_id
              where finding.attempt_id = attempt.id
          )
          and not exists (
              select 1
              from github_comment_publication_batch_item item
              join review_finding finding on finding.id = item.finding_id
              where finding.attempt_id = attempt.id
          )
        order by attempt.finished_at, attempt.id
        limit #{limit}
        """)
    List<Long> selectPayloadPurgeCandidates(
        @Param("cutoff") java.time.LocalDateTime cutoff,
        @Param("limit") int limit
    );

    @Delete("""
        <script>
        delete from changed_file
        where attempt_id in
        <foreach collection="attemptIds" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int deleteChangedFilesByAttemptIds(@Param("attemptIds") List<Long> attemptIds);

    @Delete("""
        <script>
        delete from review_finding
        where attempt_id in
        <foreach collection="attemptIds" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int deleteFindingsByAttemptIds(@Param("attemptIds") List<Long> attemptIds);

    @Update("""
        <script>
        update review_execution_attempt
        set payload_purged_at = #{purgedAt}
        where payload_purged_at is null
          and id in
        <foreach collection="attemptIds" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        </script>
        """)
    int markPayloadPurged(
        @Param("attemptIds") List<Long> attemptIds,
        @Param("purgedAt") java.time.LocalDateTime purgedAt
    );

    @Delete("""
        delete from review_execution_attempt
        where id in (
            select candidate.id
            from (
                select attempt.id
                from review_execution_attempt attempt
                join review_task task on task.id = attempt.task_id
                where attempt.finished_at < #{cutoff}
                  and attempt.status <> 'RUNNING'
                  and (task.current_attempt_id is null or task.current_attempt_id <> attempt.id)
                  and not exists (
                      select 1
                      from github_comment_publication publication
                      join review_finding finding on finding.id = publication.finding_id
                      where finding.attempt_id = attempt.id
                  )
                  and not exists (
                      select 1
                      from github_comment_publication_batch_item item
                      join review_finding finding on finding.id = item.finding_id
                      where finding.attempt_id = attempt.id
                  )
                order by attempt.finished_at, attempt.id
                limit #{limit}
            ) candidate
        )
        """)
    int deleteHistoricalAttemptMetadata(
        @Param("cutoff") java.time.LocalDateTime cutoff,
        @Param("limit") int limit
    );
}
