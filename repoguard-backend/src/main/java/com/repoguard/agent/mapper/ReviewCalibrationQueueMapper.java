package com.repoguard.agent.mapper;

import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Sample;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Summary;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewCalibrationQueueMapper {

    @Select("""
        with calibration_findings as (
            select
                finding.*,
                row_number() over (
                    partition by
                        finding.task_id,
                        lower(coalesce(nullif(trim(finding.file_path), ''), '')),
                        coalesce(finding.line_number, -1),
                        lower(coalesce(nullif(trim(finding.rule_id), ''), '')),
                        lower(coalesce(nullif(trim(finding.message), ''), ''))
                    order by finding.id
                ) as exactRank
            from review_finding finding
            join review_task task on task.id = finding.task_id
            where finding.category = 'FINDING'
              and finding.current_attempt = 1
              and finding.severity_norm in ('high', 'critical')
              and upper(coalesce(nullif(trim(task.assessment_status), ''), 'PARTIAL')) = 'COMPLETE'
              and finding.rule_config_version = #{ruleConfigVersion}
              and finding.aggregation_version = #{aggregationVersion}
              and (
                    upper(coalesce(finding.rule_id, '')) = #{ruleId}
                    or concat(
                        '/',
                        replace(replace(upper(coalesce(finding.rule_id, '')), ' ', ''), '+', '/'),
                        '/'
                    ) like concat('%/', #{ruleId}, '/%')
              )
              and concat(
                    '+',
                    replace(replace(lower(coalesce(finding.detector_version, '')), ' ', ''), '/', '+'),
                    '+'
                  ) like concat('%+', lower(#{detectorVersion}), '+%')
              and (
                    upper(coalesce(finding.source, '')) not like '%LLM%'
                    or (
                        finding.prompt_version = #{promptVersion}
                        and finding.context_version = #{contextVersion}
                        and finding.schema_version = #{schemaVersion}
                        and finding.verifier_version = #{verifierVersion}
                    )
              )
        )
        select
            count(*) as totalFindings,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as labeledCount,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as confirmedValidCount,
            sum(case
                when feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when feedback_status_norm not in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as pendingCount,
            sum(case when anchor_type <> 'NONE' then 1 else 0 end) as anchoredCount,
            sum(case when exactRank > 1 then 1 else 0 end) as duplicateCount
        from calibration_findings
        """)
    Summary selectVersionSummary(
        @Param("ruleId") String ruleId,
        @Param("detectorVersion") String detectorVersion,
        @Param("ruleConfigVersion") long ruleConfigVersion,
        @Param("promptVersion") String promptVersion,
        @Param("contextVersion") String contextVersion,
        @Param("schemaVersion") String schemaVersion,
        @Param("verifierVersion") String verifierVersion,
        @Param("aggregationVersion") String aggregationVersion
    );

    @Select("""
        with calibration_candidates as (
            select
                finding.id as findingId,
                finding.task_id as taskId,
                task.pr_number as prNumber,
                task.title as title,
                coalesce(nullif(trim(task.repository), ''), 'UNKNOWN') as repository,
                coalesce(nullif(trim(task.organization), ''), 'UNKNOWN') as organization,
                task.commit_sha as commitSha,
                task.pr_url as prUrl,
                task.created_at as taskCreatedAt,
                upper(coalesce(nullif(trim(finding.source), ''), 'UNKNOWN')) as source,
                upper(coalesce(nullif(trim(finding.rule_id), ''), 'UNASSIGNED')) as ruleId,
                upper(coalesce(nullif(trim(finding.severity), ''), 'INFO')) as severity,
                upper(coalesce(nullif(trim(finding.confidence), ''), 'LOW')) as confidence,
                finding.file_path as filePath,
                finding.line_number as lineNumber,
                finding.message as message,
                finding.evidence as evidence,
                finding.impact as impact,
                finding.recommendation as recommendation,
                finding.preconditions as preconditions,
                finding.issue_type as issueType,
                finding.verification_status as verificationStatus,
                finding.blocking_candidate as blockingCandidate,
                upper(coalesce(nullif(trim(finding.enforcement_mode), ''), 'OBSERVE')) as enforcementMode,
                finding.feedback_status_norm as feedbackStatus,
                finding.detector_version as detectorVersion,
                finding.rule_config_version as ruleConfigVersion,
                finding.policy_version as policyVersion,
                finding.prompt_version as promptVersion,
                finding.context_version as contextVersion,
                finding.schema_version as schemaVersion,
                finding.verifier_version as verifierVersion,
                finding.aggregation_version as aggregationVersion,
                row_number() over (
                    partition by
                        finding.task_id,
                        lower(coalesce(nullif(trim(finding.file_path), ''), '')),
                        coalesce(finding.line_number, -1),
                        lower(coalesce(nullif(trim(finding.rule_id), ''), '')),
                        lower(coalesce(nullif(trim(finding.message), ''), ''))
                    order by finding.id
                ) as exactRank
            from review_finding finding
            join review_task task on task.id = finding.task_id
            where finding.category = 'FINDING'
              and finding.current_attempt = 1
              and finding.severity_norm in ('high', 'critical')
              and upper(coalesce(nullif(trim(task.assessment_status), ''), 'PARTIAL')) = 'COMPLETE'
              and finding.rule_config_version = #{ruleConfigVersion}
              and finding.aggregation_version = #{aggregationVersion}
              and (
                    upper(coalesce(finding.rule_id, '')) = #{ruleId}
                    or concat(
                        '/',
                        replace(replace(upper(coalesce(finding.rule_id, '')), ' ', ''), '+', '/'),
                        '/'
                    ) like concat('%/', #{ruleId}, '/%')
              )
              and concat(
                    '+',
                    replace(replace(lower(coalesce(finding.detector_version, '')), ' ', ''), '/', '+'),
                    '+'
                  ) like concat('%+', lower(#{detectorVersion}), '+%')
              and (
                    upper(coalesce(finding.source, '')) not like '%LLM%'
                    or (
                        finding.prompt_version = #{promptVersion}
                        and finding.context_version = #{contextVersion}
                        and finding.schema_version = #{schemaVersion}
                        and finding.verifier_version = #{verifierVersion}
                    )
              )
              and (
                    finding.feedback_status_norm = 'UNREVIEWED'
                    or (#{includeIgnored} = true and finding.feedback_status_norm = 'IGNORED')
              )
        ),
        calibration_deduplicated as (
            select
                calibration_candidates.*,
                row_number() over (
                    partition by calibration_candidates.repository
                    order by calibration_candidates.taskCreatedAt desc, calibration_candidates.findingId desc
                ) as repositoryRank
            from calibration_candidates
            where calibration_candidates.exactRank = 1
        )
        select
            findingId,
            taskId,
            prNumber,
            title,
            repository,
            organization,
            commitSha,
            prUrl,
            taskCreatedAt,
            source,
            ruleId,
            severity,
            confidence,
            filePath,
            lineNumber,
            message,
            evidence,
            impact,
            recommendation,
            preconditions,
            issueType,
            verificationStatus,
            blockingCandidate,
            enforcementMode,
            feedbackStatus,
            detectorVersion,
            ruleConfigVersion,
            policyVersion,
            promptVersion,
            contextVersion,
            schemaVersion,
            verifierVersion,
            aggregationVersion
        from calibration_deduplicated
        order by repositoryRank, taskCreatedAt desc, findingId desc
        limit #{limit}
        """)
    List<Sample> selectPendingSamples(
        @Param("ruleId") String ruleId,
        @Param("detectorVersion") String detectorVersion,
        @Param("ruleConfigVersion") long ruleConfigVersion,
        @Param("promptVersion") String promptVersion,
        @Param("contextVersion") String contextVersion,
        @Param("schemaVersion") String schemaVersion,
        @Param("verifierVersion") String verifierVersion,
        @Param("aggregationVersion") String aggregationVersion,
        @Param("includeIgnored") boolean includeIgnored,
        @Param("limit") int limit
    );
}
