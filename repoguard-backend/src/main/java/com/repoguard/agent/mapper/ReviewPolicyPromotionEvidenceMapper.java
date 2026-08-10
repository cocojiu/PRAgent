package com.repoguard.agent.mapper;

import com.repoguard.agent.entity.ReviewPolicyPromotionEvidence;
import com.repoguard.agent.mapper.projection.ReviewPolicyPromotionEvidenceProjection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewPolicyPromotionEvidenceMapper {

    @Insert("""
        insert into review_policy_promotion_evidence (
            target_type,
            rule_policy_snapshot_id,
            strategy_policy_snapshot_id,
            rule_id,
            source_enforcement_mode,
            target_enforcement_mode,
            quality_baseline_version,
            quality_gate_version,
            baseline_calculated_at,
            sample_cutoff_at,
            total_samples,
            labeled_samples,
            total_high_risk_samples,
            labeled_high_risk_samples,
            confirmed_valid_samples,
            false_positive_samples,
            anchored_samples,
            duplicate_samples,
            precision_rate,
            precision_wilson_lower_bound,
            false_positive_rate,
            anchor_rate,
            duplicate_rate,
            comment_eligible,
            block_eligible,
            quality_status,
            blockers,
            sample_fingerprint,
            actor_user_id,
            actor_username,
            trace_id,
            created_at
        ) values (
            #{targetType},
            #{rulePolicySnapshotId},
            #{strategyPolicySnapshotId},
            #{ruleId},
            #{sourceEnforcementMode},
            #{targetEnforcementMode},
            #{qualityBaselineVersion},
            #{qualityGateVersion},
            #{baselineCalculatedAt},
            #{sampleCutoffAt},
            #{totalSamples},
            #{labeledSamples},
            #{totalHighRiskSamples},
            #{labeledHighRiskSamples},
            #{confirmedValidSamples},
            #{falsePositiveSamples},
            #{anchoredSamples},
            #{duplicateSamples},
            #{precision},
            #{precisionWilsonLowerBound},
            #{falsePositiveRate},
            #{anchorRate},
            #{duplicateRate},
            #{commentEligible},
            #{blockEligible},
            #{qualityStatus},
            #{blockers},
            #{sampleFingerprint},
            #{actorUserId},
            #{actorUsername},
            #{traceId},
            #{createdAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewPolicyPromotionEvidence evidence);

    @Select("""
        with promotion_samples as (
            select
                finding.id,
                finding.feedback_status_norm,
                finding.anchor_type,
                finding.feedback_at,
                task.created_at as task_created_at,
                task.finished_at as task_finished_at,
                row_number() over (
                    partition by
                        finding.task_id,
                        lower(coalesce(nullif(trim(finding.file_path), ''), '')),
                        coalesce(finding.line_number, -1),
                        lower(coalesce(nullif(trim(finding.rule_id), ''), '')),
                        lower(coalesce(nullif(trim(finding.message), ''), ''))
                    order by finding.id
                ) as exact_rank
            from review_finding finding
            join review_task task on task.id = finding.task_id
            where finding.category = 'FINDING'
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
            count(*) as totalSamples,
            sum(case when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE') then 1 else 0 end)
                as labeledSamples,
            count(*) as totalHighRiskSamples,
            sum(case when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE') then 1 else 0 end)
                as labeledHighRiskSamples,
            sum(case when feedback_status_norm in ('VALID', 'FIXED') then 1 else 0 end)
                as confirmedValidSamples,
            sum(case when feedback_status_norm = 'FALSE_POSITIVE' then 1 else 0 end)
                as falsePositiveSamples,
            sum(case when anchor_type <> 'NONE' then 1 else 0 end) as anchoredSamples,
            sum(case when exact_rank > 1 then 1 else 0 end) as duplicateSamples,
            max(greatest(
                coalesce(feedback_at, timestamp('1970-01-01 00:00:00')),
                coalesce(task_finished_at, task_created_at, timestamp('1970-01-01 00:00:00'))
            )) as sampleCutoffAt,
            sha2(concat_ws('|',
                count(*),
                coalesce(min(id), 0),
                coalesce(max(id), 0),
                coalesce(sum(cast(id as decimal(65, 0))), 0),
                coalesce(bit_xor(cast(id as unsigned)), 0),
                coalesce(bit_xor(crc32(concat_ws('|',
                    id,
                    coalesce(feedback_status_norm, 'UNREVIEWED'),
                    coalesce(anchor_type, 'NONE'),
                    exact_rank
                ))), 0)
            ), 256) as sampleFingerprint
        from promotion_samples
        """)
    ReviewPolicyPromotionEvidenceProjection selectRuleEvidence(
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
        with promotion_samples as (
            select
                finding.id,
                finding.severity_norm,
                finding.feedback_status_norm,
                finding.anchor_type,
                finding.feedback_at,
                task.created_at as task_created_at,
                task.finished_at as task_finished_at,
                row_number() over (
                    partition by
                        finding.task_id,
                        lower(coalesce(nullif(trim(finding.file_path), ''), '')),
                        coalesce(finding.line_number, -1),
                        lower(coalesce(nullif(trim(finding.rule_id), ''), '')),
                        lower(coalesce(nullif(trim(finding.message), ''), '')),
                        finding.detector_version,
                        finding.rule_config_version,
                        finding.policy_version,
                        finding.prompt_version,
                        finding.context_version,
                        finding.schema_version,
                        finding.verifier_version,
                        finding.aggregation_version
                    order by finding.id
                ) as exact_rank
            from review_finding finding
            join review_task task on task.id = finding.task_id
            where finding.category = 'FINDING'
              and upper(coalesce(nullif(trim(task.assessment_status), ''), 'PARTIAL')) = 'COMPLETE'
              and upper(coalesce(finding.source, '')) like '%LLM%'
              and finding.prompt_version = #{promptVersion}
              and finding.context_version = #{contextVersion}
              and finding.schema_version = #{schemaVersion}
              and finding.verifier_version = #{verifierVersion}
              and finding.aggregation_version = #{aggregationVersion}
        )
        select
            count(*) as totalSamples,
            sum(case when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE') then 1 else 0 end)
                as labeledSamples,
            sum(case when severity_norm in ('high', 'critical') then 1 else 0 end) as totalHighRiskSamples,
            sum(case
                when severity_norm in ('high', 'critical')
                 and feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as labeledHighRiskSamples,
            sum(case
                when severity_norm in ('high', 'critical')
                 and feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as confirmedValidSamples,
            sum(case
                when severity_norm in ('high', 'critical')
                 and feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveSamples,
            sum(case
                when severity_norm in ('high', 'critical') and anchor_type <> 'NONE'
                then 1 else 0 end) as anchoredSamples,
            sum(case
                when severity_norm in ('high', 'critical') and exact_rank > 1
                then 1 else 0 end) as duplicateSamples,
            max(greatest(
                coalesce(feedback_at, timestamp('1970-01-01 00:00:00')),
                coalesce(task_finished_at, task_created_at, timestamp('1970-01-01 00:00:00'))
            )) as sampleCutoffAt,
            sha2(concat_ws('|',
                count(*),
                coalesce(min(id), 0),
                coalesce(max(id), 0),
                coalesce(sum(cast(id as decimal(65, 0))), 0),
                coalesce(bit_xor(cast(id as unsigned)), 0),
                coalesce(bit_xor(crc32(concat_ws('|',
                    id,
                    coalesce(severity_norm, 'info'),
                    coalesce(feedback_status_norm, 'UNREVIEWED'),
                    coalesce(anchor_type, 'NONE'),
                    exact_rank
                ))), 0)
            ), 256) as sampleFingerprint
        from promotion_samples
        """)
    ReviewPolicyPromotionEvidenceProjection selectStrategyEvidence(
        @Param("promptVersion") String promptVersion,
        @Param("contextVersion") String contextVersion,
        @Param("schemaVersion") String schemaVersion,
        @Param("verifierVersion") String verifierVersion,
        @Param("aggregationVersion") String aggregationVersion
    );
}
