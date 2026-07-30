package com.repoguard.agent.mapper;

import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityModelStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityRepositoryStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityTrendCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.MetricStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.ReviewTrendCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.RiskLevelCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.RuleHitCount;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DashboardDailySnapshotMapper {

    @Select("""
        select max(stat_date)
        from dashboard_review_daily_stat
        """)
    LocalDate selectLatestReviewSnapshotDate();

    @Select("""
        select min(stat_date)
        from dashboard_review_daily_stat
        """)
    LocalDate selectEarliestReviewSnapshotDate();

    @Select("""
        select max(stat_date)
        from dashboard_llm_quality_daily_stat
        """)
    LocalDate selectLatestLlmQualitySnapshotDate();

    @Select("""
        select min(stat_date)
        from dashboard_llm_quality_daily_stat
        """)
    LocalDate selectEarliestLlmQualitySnapshotDate();

    @Delete("""
        delete from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        """)
    int deleteReviewDailyStatsFrom(@Param("startDate") LocalDate startDate);

    @Insert("""
        insert into dashboard_review_daily_stat (
            stat_date,
            task_count,
            high_risk_count,
            medium_risk_count,
            low_risk_count,
            info_risk_count,
            failed_count,
            duration_seconds_sum,
            duration_sample_count
        )
        select
            created_date as stat_date,
            count(*) as task_count,
            sum(case when risk_bucket_norm = 'HIGH' then 1 else 0 end) as high_risk_count,
            sum(case when risk_bucket_norm = 'MEDIUM' then 1 else 0 end) as medium_risk_count,
            sum(case when risk_bucket_norm = 'LOW' then 1 else 0 end) as low_risk_count,
            sum(case when risk_bucket_norm = 'INFO' then 1 else 0 end) as info_risk_count,
            sum(case when status_norm = 'FAILED' then 1 else 0 end) as failed_count,
            sum(case when finished_at is not null then duration_seconds else 0 end) as duration_seconds_sum,
            sum(case when finished_at is not null then 1 else 0 end) as duration_sample_count
        from review_task
        where created_at >= #{startDate}
        group by created_date
        """)
    int insertReviewDailyStatsFromTasks(@Param("startDate") LocalDate startDate);

    @Delete("""
        delete from dashboard_rule_daily_stat
        where stat_date >= #{startDate}
        """)
    int deleteRuleDailyStatsFrom(@Param("startDate") LocalDate startDate);

    @Insert("""
        insert into dashboard_rule_daily_stat (
            stat_date,
            rule_id,
            total_count
        )
        select
            t.created_date as stat_date,
            coalesce(f.rule_id, 'LLM') as rule_id,
            count(*) as total_count
        from review_finding f
        join review_task t on t.id = f.task_id
        where f.category = 'FINDING'
          and t.created_at >= #{startDate}
        group by t.created_date, coalesce(f.rule_id, 'LLM')
        """)
    int insertRuleDailyStatsFromFindings(@Param("startDate") LocalDate startDate);

    @Delete("""
        delete from dashboard_llm_quality_daily_stat
        where stat_date >= #{startDate}
        """)
    int deleteLlmQualityDailyStatsFrom(@Param("startDate") LocalDate startDate);

    @Insert("""
        insert into dashboard_llm_quality_daily_stat (
            stat_date,
            model_label,
            repository_label,
            task_count,
            duration_ms_sum,
            duration_sample_count,
            token_sum,
            token_sample_count,
            cost_sum,
            cost_sample_count,
            parse_success_count,
            fallback_count,
            partial_fallback_count,
            reviewed_feedback_count,
            valid_feedback_count,
            false_positive_feedback_count
        )
        select
            task_stats.stat_date as stat_date,
            task_stats.model_label as model_label,
            task_stats.repository_label as repository_label,
            task_stats.task_count as task_count,
            task_stats.duration_ms_sum as duration_ms_sum,
            task_stats.duration_sample_count as duration_sample_count,
            task_stats.token_sum as token_sum,
            task_stats.token_sample_count as token_sample_count,
            task_stats.cost_sum as cost_sum,
            task_stats.cost_sample_count as cost_sample_count,
            task_stats.parse_success_count as parse_success_count,
            task_stats.fallback_count as fallback_count,
            task_stats.partial_fallback_count as partial_fallback_count,
            coalesce(feedback_stats.reviewed_feedback_count, 0) as reviewed_feedback_count,
            coalesce(feedback_stats.valid_feedback_count, 0) as valid_feedback_count,
            coalesce(feedback_stats.false_positive_feedback_count, 0) as false_positive_feedback_count
        from (
            select
                created_date as stat_date,
                llm_model_label as model_label,
                repository_label as repository_label,
                count(*) as task_count,
                sum(coalesce(llm_duration_ms, 0)) as duration_ms_sum,
                sum(case when llm_duration_ms is not null then 1 else 0 end) as duration_sample_count,
                sum(case when llm_total_tokens is not null and llm_total_tokens > 0 then llm_total_tokens else 0 end) as token_sum,
                sum(case when llm_total_tokens is not null and llm_total_tokens > 0 then 1 else 0 end) as token_sample_count,
                sum(coalesce(llm_estimated_cost, 0)) as cost_sum,
                sum(case when llm_estimated_cost is not null then 1 else 0 end) as cost_sample_count,
                sum(case
                    when (llm_parse_status_norm = 'parsed'
                        or (llm_parse_status_norm = '' and llm_status_norm = 'completed'))
                        and llm_status_norm <> 'fallback'
                    then 1 else 0 end) as parse_success_count,
                sum(case
                    when llm_status_norm = 'fallback'
                        or llm_parse_status_norm = 'fallback'
                    then 1 else 0 end) as fallback_count,
                sum(case
                    when llm_parse_status_norm = 'partial_fallback'
                    then 1 else 0 end) as partial_fallback_count
            from review_task
            where llm_status_norm <> ''
              and llm_status_norm <> 'pending'
              and created_at >= #{startDate}
            group by created_date, llm_model_label, repository_label
        ) task_stats
        left join (
            select
                t.created_date as stat_date,
                t.llm_model_label as model_label,
                t.repository_label as repository_label,
                sum(case
                    when f.feedback_status_norm <> 'UNREVIEWED'
                    then 1 else 0 end) as reviewed_feedback_count,
                sum(case
                    when f.feedback_status_norm = 'VALID'
                    then 1 else 0 end) as valid_feedback_count,
                sum(case
                    when f.feedback_status_norm = 'FALSE_POSITIVE'
                    then 1 else 0 end) as false_positive_feedback_count
            from review_task t
            join review_finding f on f.task_id = t.id and f.category = 'FINDING'
            where t.llm_status_norm <> ''
              and t.llm_status_norm <> 'pending'
              and t.created_at >= #{startDate}
            group by t.created_date, t.llm_model_label, t.repository_label
        ) feedback_stats on feedback_stats.stat_date = task_stats.stat_date
            and feedback_stats.model_label = task_stats.model_label
            and feedback_stats.repository_label = task_stats.repository_label
        """)
    int insertLlmQualityDailyStatsFromTasks(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            coalesce(sum(task_count), 0) as total,
            coalesce(sum(high_risk_count), 0) as highRisk,
            coalesce(sum(failed_count), 0) as failed,
            sum(duration_seconds_sum) / nullif(sum(duration_sample_count), 0) as averageDurationSeconds
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        """)
    MetricStat selectMetricStat(@Param("startDate") LocalDate startDate);

    @Select("""
        select date_format(stat_date, '%m-%d') as dayLabel, task_count as total
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        order by stat_date
        """)
    List<ReviewTrendCount> selectReviewTrendCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select 'HIGH' as riskLevel, coalesce(sum(high_risk_count), 0) as total
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        union all
        select 'MEDIUM' as riskLevel, coalesce(sum(medium_risk_count), 0) as total
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        union all
        select 'LOW' as riskLevel, coalesce(sum(low_risk_count), 0) as total
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        union all
        select 'INFO' as riskLevel, coalesce(sum(info_risk_count), 0) as total
        from dashboard_review_daily_stat
        where stat_date >= #{startDate}
        """)
    List<RiskLevelCount> selectRiskLevelCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select rule_id as ruleId, sum(total_count) as total
        from dashboard_rule_daily_stat
        where stat_date >= #{startDate}
        group by rule_id
        """)
    List<RuleHitCount> selectRuleHitCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            date_format(stat_date, '%Y-%m-%d') as dayKey,
            sum(task_count) as taskCount,
            sum(parse_success_count) as parseSuccessCount,
            sum(fallback_count) as fallbackCount,
            sum(partial_fallback_count) as partialFallbackCount
        from dashboard_llm_quality_daily_stat
        where stat_date >= #{startDate}
        group by stat_date
        """)
    List<LlmQualityTrendCount> selectLlmQualityTrendCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            model_label as modelLabel,
            sum(task_count) as taskCount,
            sum(duration_ms_sum) / nullif(sum(duration_sample_count), 0) as averageDurationMs,
            sum(token_sum) / nullif(sum(token_sample_count), 0) as averageTokens,
            sum(cost_sum) / nullif(sum(cost_sample_count), 0) as averageCost,
            sum(parse_success_count) as parseSuccessCount,
            sum(fallback_count) as fallbackCount,
            sum(partial_fallback_count) as partialFallbackCount,
            sum(reviewed_feedback_count) as reviewedFeedbackCount,
            sum(valid_feedback_count) as validFeedbackCount,
            sum(false_positive_feedback_count) as falsePositiveFeedbackCount
        from dashboard_llm_quality_daily_stat
        where stat_date >= #{startDate}
        group by model_label
        order by taskCount desc
        limit 6
        """)
    List<LlmQualityModelStat> selectLlmQualityByModelStats(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            repository_label as repositoryLabel,
            sum(task_count) as taskCount,
            sum(fallback_count) as fallbackCount,
            sum(partial_fallback_count) as partialFallbackCount,
            sum(reviewed_feedback_count) as reviewedFeedbackCount,
            sum(valid_feedback_count) as validFeedbackCount,
            sum(false_positive_feedback_count) as falsePositiveFeedbackCount
        from dashboard_llm_quality_daily_stat
        where stat_date >= #{startDate}
        group by repository_label
        order by taskCount desc
        limit 6
        """)
    List<LlmQualityRepositoryStat> selectLlmQualityByRepositoryStats(@Param("startDate") LocalDate startDate);
}
