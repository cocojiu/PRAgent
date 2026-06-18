package com.repoguard.agent.mapper;

import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DashboardMapper {

    @Select("""
        select
            count(*) as total,
            sum(case when risk_level in ('HIGH', 'CRITICAL') then 1 else 0 end) as highRisk,
            sum(case when status = 'FAILED' then 1 else 0 end) as failed,
            avg(coalesce(duration_seconds, 0)) as averageDurationSeconds
        from review_task
        where created_at >= #{startDate}
        """)
    DashboardMetricStat selectMetricStat(@Param("startDate") LocalDate startDate);

    @Select("""
        select risk_level as riskLevel, count(*) as total
        from review_task
        where created_at >= #{startDate}
        group by risk_level
        """)
    List<DashboardRiskLevelCount> selectRiskLevelCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select date_format(created_at, '%m-%d') as dayLabel, count(*) as total
        from review_task
        where created_at >= #{startDate}
        group by date_format(created_at, '%m-%d')
        order by dayLabel
        """)
    List<DashboardReviewTrendCount> selectReviewTrendCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select coalesce(f.rule_id, 'LLM') as ruleId, count(*) as total
        from review_finding f
        join review_task t on t.id = f.task_id
        where f.category = 'FINDING'
          and t.created_at >= #{startDate}
        group by coalesce(f.rule_id, 'LLM')
        """)
    List<DashboardRuleHitCount> selectRuleHitCounts(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            t.title as title,
            t.repository as repository,
            t.risk_level as riskLevel,
            count(f.id) as ruleHits,
            t.created_at as createdAt,
            t.status as status
        from review_task t
        left join review_finding f on f.task_id = t.id and f.category = 'FINDING'
        where t.risk_level in ('HIGH', 'CRITICAL')
          and t.created_at >= #{startDate}
        group by t.id, t.title, t.repository, t.risk_level, t.created_at, t.status
        order by t.created_at desc
        limit 5
        """)
    List<DashboardHighRiskReview> selectRecentHighRiskReviews(@Param("startDate") LocalDate startDate);

    @Select("""
        select
            date_format(created_at, '%Y-%m-%d') as dayKey,
            count(*) as taskCount,
            sum(case
                when (llm_parse_status = 'PARSED'
                    or ((llm_parse_status is null or llm_parse_status = '') and llm_status = 'COMPLETED'))
                    and llm_status <> 'FALLBACK'
                then 1 else 0 end) as parseSuccessCount,
            sum(case
                when llm_status = 'FALLBACK' or llm_parse_status = 'FALLBACK'
                then 1 else 0 end) as fallbackCount,
            sum(case
                when llm_parse_status = 'PARTIAL_FALLBACK'
                then 1 else 0 end) as partialFallbackCount
        from review_task
        where llm_status is not null
          and llm_status <> ''
          and llm_status <> 'PENDING'
          and created_at >= #{startDate}
        group by date_format(created_at, '%Y-%m-%d')
        """)
    List<DashboardLlmQualityTrendCount> selectLlmQualityTrendCounts(@Param("startDate") LocalDate startDate);

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
                concat(
                    coalesce(nullif(trim(llm_provider), ''), 'unknown'),
                    ' / ',
                    coalesce(nullif(trim(llm_model), ''), 'unknown')
                ) as modelLabel,
                count(*) as taskCount,
                avg(coalesce(llm_duration_ms, 0)) as averageDurationMs,
                avg(case when llm_total_tokens is not null and llm_total_tokens > 0 then llm_total_tokens end) as averageTokens,
                avg(llm_estimated_cost) as averageCost,
                sum(case
                    when (llm_parse_status = 'PARSED'
                        or ((llm_parse_status is null or llm_parse_status = '') and llm_status = 'COMPLETED'))
                        and llm_status <> 'FALLBACK'
                    then 1 else 0 end) as parseSuccessCount,
                sum(case
                    when llm_status = 'FALLBACK' or llm_parse_status = 'FALLBACK'
                    then 1 else 0 end) as fallbackCount,
                sum(case
                    when llm_parse_status = 'PARTIAL_FALLBACK'
                    then 1 else 0 end) as partialFallbackCount
            from review_task
            where llm_status is not null
              and llm_status <> ''
              and llm_status <> 'PENDING'
              and created_at >= #{startDate}
            group by modelLabel
        ) task_stats
        left join (
            select
                concat(
                    coalesce(nullif(trim(t.llm_provider), ''), 'unknown'),
                    ' / ',
                    coalesce(nullif(trim(t.llm_model), ''), 'unknown')
                ) as modelLabel,
                sum(case
                    when f.feedback_status is not null and f.feedback_status <> '' and f.feedback_status <> 'UNREVIEWED'
                    then 1 else 0 end) as reviewedFeedbackCount,
                sum(case when f.feedback_status = 'VALID' then 1 else 0 end) as validFeedbackCount,
                sum(case when f.feedback_status = 'FALSE_POSITIVE' then 1 else 0 end) as falsePositiveFeedbackCount
            from review_task t
            join review_finding f on f.task_id = t.id and f.category = 'FINDING'
            where t.llm_status is not null
              and t.llm_status <> ''
              and t.llm_status <> 'PENDING'
              and t.created_at >= #{startDate}
            group by modelLabel
        ) feedback_stats on feedback_stats.modelLabel = task_stats.modelLabel
        order by task_stats.taskCount desc
        limit 6
    """)
    List<DashboardLlmQualityModelStat> selectLlmQualityByModelStats(@Param("startDate") LocalDate startDate);

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
                case
                    when organization is null or trim(organization) = ''
                    then coalesce(nullif(trim(repository), ''), 'unknown')
                    else concat(trim(organization), '/', coalesce(nullif(trim(repository), ''), 'unknown'))
                end as repositoryLabel,
                count(*) as taskCount,
                sum(case
                    when llm_status = 'FALLBACK' or llm_parse_status = 'FALLBACK'
                    then 1 else 0 end) as fallbackCount,
                sum(case
                    when llm_parse_status = 'PARTIAL_FALLBACK'
                    then 1 else 0 end) as partialFallbackCount
            from review_task
            where llm_status is not null
              and llm_status <> ''
              and llm_status <> 'PENDING'
              and created_at >= #{startDate}
            group by repositoryLabel
        ) task_stats
        left join (
            select
                case
                    when t.organization is null or trim(t.organization) = ''
                    then coalesce(nullif(trim(t.repository), ''), 'unknown')
                    else concat(trim(t.organization), '/', coalesce(nullif(trim(t.repository), ''), 'unknown'))
                end as repositoryLabel,
                sum(case
                    when f.feedback_status is not null and f.feedback_status <> '' and f.feedback_status <> 'UNREVIEWED'
                    then 1 else 0 end) as reviewedFeedbackCount,
                sum(case when f.feedback_status = 'VALID' then 1 else 0 end) as validFeedbackCount,
                sum(case when f.feedback_status = 'FALSE_POSITIVE' then 1 else 0 end) as falsePositiveFeedbackCount
            from review_task t
            join review_finding f on f.task_id = t.id and f.category = 'FINDING'
            where t.llm_status is not null
              and t.llm_status <> ''
              and t.llm_status <> 'PENDING'
              and t.created_at >= #{startDate}
            group by repositoryLabel
        ) feedback_stats on feedback_stats.repositoryLabel = task_stats.repositoryLabel
        order by task_stats.taskCount desc
        limit 6
        """)
    List<DashboardLlmQualityRepositoryStat> selectLlmQualityByRepositoryStats(@Param("startDate") LocalDate startDate);
}
