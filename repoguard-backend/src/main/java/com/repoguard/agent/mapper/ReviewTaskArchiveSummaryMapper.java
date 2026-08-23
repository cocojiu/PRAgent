package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewTaskArchiveSummary;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewTaskArchiveSummaryMapper extends BaseMapper<ReviewTaskArchiveSummary> {

    @Insert("""
        <script>
        insert into review_task_archive_summary (
            task_id, cleanup_batch_id,
            organization, repository, pr_number, title, commit_sha, branch_name,
            status, risk_level, assessment_status, source, trigger_source,
            created_at, finished_at, duration_seconds,
            finding_count, missing_test_count, changed_file_count, timeline_count, publication_count,
            backup_reference, archived_at
        )
        select
            task.id as task_id,
            #{cleanupBatchId} as cleanup_batch_id,
            task.organization,
            task.repository,
            task.pr_number,
            task.title,
            task.commit_sha,
            task.branch_name,
            task.status,
            task.risk_level,
            task.assessment_status,
            task.source,
            task.trigger_source,
            task.created_at,
            task.finished_at,
            coalesce(task.duration_seconds, 0) as duration_seconds,
            coalesce(finding_counts.finding_count, 0) as finding_count,
            coalesce(missing_test_counts.missing_test_count, 0) as missing_test_count,
            coalesce(changed_file_counts.changed_file_count, 0) as changed_file_count,
            coalesce(timeline_counts.timeline_count, 0) as timeline_count,
            coalesce(publication_counts.publication_count, 0) as publication_count,
            #{backupReference} as backup_reference,
            now() as archived_at
        from review_task task
        left join (
            select task_id, count(*) as finding_count
            from review_finding
            where category = 'FINDING'
              and current_attempt = 1
              and task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
            group by task_id
        ) finding_counts on finding_counts.task_id = task.id
        left join (
            select task_id, count(*) as missing_test_count
            from review_finding
            where category = 'MISSING_TEST'
              and current_attempt = 1
              and task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
            group by task_id
        ) missing_test_counts on missing_test_counts.task_id = task.id
        left join (
            select task_id, count(*) as changed_file_count
            from changed_file
            where current_attempt = 1
              and task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
            group by task_id
        ) changed_file_counts on changed_file_counts.task_id = task.id
        left join (
            select task_id, count(*) as timeline_count
            from review_timeline
            where task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
            group by task_id
        ) timeline_counts on timeline_counts.task_id = task.id
        left join (
            select task_id, count(*) as publication_count
            from github_comment_publication
            where task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
            group by task_id
        ) publication_counts on publication_counts.task_id = task.id
        where task.id in
        <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
            #{taskId}
        </foreach>
        on duplicate key update
            cleanup_batch_id = values(cleanup_batch_id),
            organization = values(organization),
            repository = values(repository),
            pr_number = values(pr_number),
            title = values(title),
            commit_sha = values(commit_sha),
            branch_name = values(branch_name),
            status = values(status),
            risk_level = values(risk_level),
            assessment_status = values(assessment_status),
            source = values(source),
            trigger_source = values(trigger_source),
            created_at = values(created_at),
            finished_at = values(finished_at),
            duration_seconds = values(duration_seconds),
            finding_count = values(finding_count),
            missing_test_count = values(missing_test_count),
            changed_file_count = values(changed_file_count),
            timeline_count = values(timeline_count),
            publication_count = values(publication_count),
            backup_reference = values(backup_reference),
            archived_at = values(archived_at)
        </script>
        """)
    int insertArchiveSummaries(
        @Param("cleanupBatchId") long cleanupBatchId,
        @Param("backupReference") String backupReference,
        @Param("taskIds") List<Long> taskIds
    );

    @Select("""
        select *
        from review_task_archive_summary
        where task_id = #{taskId}
        """)
    ReviewTaskArchiveSummary selectByTaskId(@Param("taskId") Long taskId);
}
