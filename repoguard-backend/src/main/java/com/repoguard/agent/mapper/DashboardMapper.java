package com.repoguard.agent.mapper;

import com.repoguard.agent.mapper.projection.DashboardProjections.HighRiskReview;
import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityModelStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityRepositoryStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.LlmQualityTrendCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.MetricStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.ReviewTrendCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.RiskLevelCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.RuleHitCount;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DashboardMapper {

    @Select("""
        select date(max(created_at))
        from review_task
        """)
    LocalDate selectLatestReviewTaskDate();

    @Select("""
        select
            count(*) as total,
            sum(case
                when risk_level_norm in ('HIGH', 'CRITICAL')
                then 1 else 0 end) as highRisk,
            sum(case
                when status_norm = 'FAILED'
                then 1 else 0 end) as failed,
            avg(case when finished_at is not null then duration_seconds end) as averageDurationSeconds
        from review_task
        where created_at >= #{startDate}
        """)
    MetricStat selectMetricStat(@Param("startDate") LocalDate startDate);

    @Select("""
        select risk_bucket_norm as riskLevel, count(*) as total
        from review_task
        where created_at >= #{startDate}
        group by risk_bucket_norm
        """)
    List<RiskLevelCount> selectRiskLevelCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select date_format(created_date, '%m-%d') as dayLabel, count(*) as total
        from review_task
        where created_at >= #{startDate}
        group by created_date
        order by created_date
        """)
    List<ReviewTrendCount> selectReviewTrendCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select coalesce(f.rule_id, 'LLM') as ruleId, count(*) as total
        from review_finding f
        join review_task t on t.id = f.task_id
        where f.category = 'FINDING'
          and t.created_at >= #{startDate}
        group by coalesce(f.rule_id, 'LLM')
        """)
    List<RuleHitCount> selectRuleHitCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            t.title as title,
            t.repository as repository,
            t.risk_level_norm as riskLevel,
            count(f.id) as ruleHits,
            t.created_at as createdAt,
            t.status as status
        from review_task t
        left join review_finding f on f.task_id = t.id and f.category = 'FINDING'
        where t.risk_level_norm in ('HIGH', 'CRITICAL')
          and t.created_at >= #{startDate}
        group by t.id, t.title, t.repository, t.risk_level_norm, t.created_at, t.status
        order by t.created_at desc
        limit 5
        """)
    List<HighRiskReview> selectRecentHighRiskReviews(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            date_format(created_date, '%Y-%m-%d') as dayKey,
            count(*) as taskCount,
            sum(case
                when (llm_parse_status_norm = 'parsed'
                    or (llm_parse_status_norm = '' and llm_status_norm = 'completed'))
                    and llm_status_norm <> 'fallback'
                then 1 else 0 end) as parseSuccessCount,
            sum(case
                when llm_status_norm = 'fallback'
                    or llm_parse_status_norm = 'fallback'
                then 1 else 0 end) as fallbackCount,
            sum(case
                when llm_parse_status_norm = 'partial_fallback'
                then 1 else 0 end) as partialFallbackCount
        from review_task
        where llm_status_norm <> ''
          and llm_status_norm <> 'pending'
          and created_at >= #{startDate}
        group by created_date
        """)
    List<LlmQualityTrendCount> selectLlmQualityTrendCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            task_stats.modelLabel as modelLabel,
            task_stats.taskCount as taskCount,
            task_stats.averageDurationMs as averageDurationMs,
            task_stats.averageTokens as averageTokens,
            task_stats.averageCost as averageCost,
            task_stats.parseSuccessCount as parseSuccessCount,
            task_stats.fallbackCount as fallbackCount,
            task_stats.partialFallbackCount as partialFallbackCount,
            coalesce(feedback_stats.reviewedFeedbackCount, 0) as reviewedFeedbackCount,
            coalesce(feedback_stats.validFeedbackCount, 0) as validFeedbackCount,
            coalesce(feedback_stats.falsePositiveFeedbackCount, 0) as falsePositiveFeedbackCount
        from (
            select
                llm_model_label as modelLabel,
                count(*) as taskCount,
                avg(llm_duration_ms) as averageDurationMs,
                avg(case when llm_total_tokens is not null and llm_total_tokens > 0 then llm_total_tokens end) as averageTokens,
                avg(llm_estimated_cost) as averageCost,
                sum(case
                    when (llm_parse_status_norm = 'parsed'
                        or (llm_parse_status_norm = '' and llm_status_norm = 'completed'))
                        and llm_status_norm <> 'fallback'
                    then 1 else 0 end) as parseSuccessCount,
                sum(case
                    when llm_status_norm = 'fallback'
                        or llm_parse_status_norm = 'fallback'
                    then 1 else 0 end) as fallbackCount,
                sum(case
                    when llm_parse_status_norm = 'partial_fallback'
                    then 1 else 0 end) as partialFallbackCount
            from review_task
            where llm_status_norm <> ''
              and llm_status_norm <> 'pending'
              and created_at >= #{startDate}
            group by modelLabel
        ) task_stats
        left join (
            select
                t.llm_model_label as modelLabel,
                sum(case
                    when f.feedback_status_norm <> 'UNREVIEWED'
                    then 1 else 0 end) as reviewedFeedbackCount,
                sum(case
                    when f.feedback_status_norm = 'VALID'
                    then 1 else 0 end) as validFeedbackCount,
                sum(case
                    when f.feedback_status_norm = 'FALSE_POSITIVE'
                    then 1 else 0 end) as falsePositiveFeedbackCount
            from review_task t
            join review_finding f on f.task_id = t.id and f.category = 'FINDING'
            where t.llm_status_norm <> ''
              and t.llm_status_norm <> 'pending'
              and t.created_at >= #{startDate}
            group by modelLabel
        ) feedback_stats on feedback_stats.modelLabel = task_stats.modelLabel
        order by task_stats.taskCount desc
        limit 6
    """)
    List<LlmQualityModelStat> selectLlmQualityByModelStats(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            task_stats.repositoryLabel as repositoryLabel,
            task_stats.taskCount as taskCount,
            task_stats.fallbackCount as fallbackCount,
            task_stats.partialFallbackCount as partialFallbackCount,
            coalesce(feedback_stats.reviewedFeedbackCount, 0) as reviewedFeedbackCount,
            coalesce(feedback_stats.validFeedbackCount, 0) as validFeedbackCount,
            coalesce(feedback_stats.falsePositiveFeedbackCount, 0) as falsePositiveFeedbackCount
        from (
            select
                repository_label as repositoryLabel,
                count(*) as taskCount,
                sum(case
                    when llm_status_norm = 'fallback'
                        or llm_parse_status_norm = 'fallback'
                    then 1 else 0 end) as fallbackCount,
                sum(case
                    when llm_parse_status_norm = 'partial_fallback'
                    then 1 else 0 end) as partialFallbackCount
            from review_task
            where llm_status_norm <> ''
              and llm_status_norm <> 'pending'
              and created_at >= #{startDate}
            group by repositoryLabel
        ) task_stats
        left join (
            select
                t.repository_label as repositoryLabel,
                sum(case
                    when f.feedback_status_norm <> 'UNREVIEWED'
                    then 1 else 0 end) as reviewedFeedbackCount,
                sum(case
                    when f.feedback_status_norm = 'VALID'
                    then 1 else 0 end) as validFeedbackCount,
                sum(case
                    when f.feedback_status_norm = 'FALSE_POSITIVE'
                    then 1 else 0 end) as falsePositiveFeedbackCount
            from review_task t
            join review_finding f on f.task_id = t.id and f.category = 'FINDING'
            where t.llm_status_norm <> ''
              and t.llm_status_norm <> 'pending'
              and t.created_at >= #{startDate}
            group by repositoryLabel
        ) feedback_stats on feedback_stats.repositoryLabel = task_stats.repositoryLabel
        order by task_stats.taskCount desc
        limit 6
        """)
    List<LlmQualityRepositoryStat> selectLlmQualityByRepositoryStats(@Param("startDate") LocalDate startDate);
}
