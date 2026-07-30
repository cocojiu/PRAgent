package com.repoguard.agent.mapper;

import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Execution;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Group;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Summary;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface ReviewQualityBaselineMapper {

    @Select("""
        select
            count(*) as totalFindings,
            sum(case
                when finding.severity_norm in ('high', 'critical')
                then 1 else 0 end) as highRiskFindings,
            sum(case
                when finding.severity_norm in ('high', 'critical')
                 and finding.feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as labeledHighRiskFindings,
            sum(case
                when finding.severity_norm in ('high', 'critical')
                 and finding.feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as confirmedHighRiskFindings,
            sum(case
                when finding.severity_norm in ('high', 'critical')
                 and finding.feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveHighRiskFindings,
            sum(case
                when finding.line_number is not null and finding.line_number > 0
                then 1 else 0 end) as anchoredFindings,
            (
                select coalesce(sum(duplicates.exactCount - 1), 0)
                from (
                    select count(*) as exactCount
                    from review_finding duplicateFinding
                    where duplicateFinding.category = 'FINDING'
                    group by
                        duplicateFinding.task_id,
                        lower(coalesce(nullif(trim(duplicateFinding.file_path), ''), '')),
                        coalesce(duplicateFinding.line_number, -1),
                        lower(coalesce(nullif(trim(duplicateFinding.rule_id), ''), '')),
                        lower(coalesce(nullif(trim(duplicateFinding.message), ''), ''))
                    having count(*) > 1
                ) duplicates
            ) as duplicateFindings
        from review_finding finding
        where finding.category = 'FINDING'
        """)
    Summary selectSummary();

    @Select("""
        select
            coalesce(nullif(trim(finding.rule_id), ''), 'UNASSIGNED') as ruleId,
            upper(coalesce(nullif(trim(finding.source), ''), 'UNKNOWN')) as source,
            coalesce(nullif(trim(task.repository), ''), 'UNKNOWN') as repository,
            case
                when lower(coalesce(finding.file_path, '')) like '%.java' then 'JAVA'
                when lower(coalesce(finding.file_path, '')) like '%.kt' then 'KOTLIN'
                when lower(coalesce(finding.file_path, '')) like '%.sql' then 'SQL'
                when lower(coalesce(finding.file_path, '')) like '%.yml'
                  or lower(coalesce(finding.file_path, '')) like '%.yaml' then 'YAML'
                when lower(coalesce(finding.file_path, '')) like '%.properties' then 'PROPERTIES'
                when lower(coalesce(finding.file_path, '')) like '%.ts'
                  or lower(coalesce(finding.file_path, '')) like '%.tsx' then 'TYPESCRIPT'
                when lower(coalesce(finding.file_path, '')) like '%.js'
                  or lower(coalesce(finding.file_path, '')) like '%.jsx' then 'JAVASCRIPT'
                else 'OTHER'
            end as language,
            upper(coalesce(nullif(trim(finding.severity), ''), 'INFO')) as severity,
            count(*) as totalFindings,
            sum(case
                when finding.feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as confirmedValidCount,
            sum(case
                when finding.feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when finding.feedback_status_norm not in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as pendingCount,
            sum(case
                when finding.line_number is not null and finding.line_number > 0
                then 1 else 0 end) as anchoredCount
        from review_finding finding
        join review_task task on task.id = finding.task_id
        where finding.category = 'FINDING'
        group by ruleId, source, repository, language, severity
        order by ruleId, source, repository, language, severity
        """)
    List<Group> selectGroups();

    @Select("""
        select
            count(*) as completedTasks,
            coalesce(avg(task.duration_seconds), 0) as averageDurationSeconds,
            coalesce(sum(task.llm_estimated_cost), 0) as totalLlmEstimatedCost
        from review_task task
        where task.finished_at is not null
        """)
    Execution selectExecution();
}
