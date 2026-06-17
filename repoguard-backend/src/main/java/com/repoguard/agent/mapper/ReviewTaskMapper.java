package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    @Select("""
        select risk_level as riskLevel, count(*) as total
        from review_task
        group by risk_level
        """)
    List<DashboardRiskLevelCount> selectRiskLevelCounts();

    @Select("""
        select date_format(created_at, '%m-%d') as dayLabel, count(*) as total
        from review_task
        group by date_format(created_at, '%m-%d')
        order by dayLabel
        """)
    List<DashboardReviewTrendCount> selectReviewTrendCounts();

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
        group by t.id, t.title, t.repository, t.risk_level, t.created_at, t.status
        order by t.created_at desc
        limit 5
        """)
    List<DashboardHighRiskReview> selectRecentHighRiskReviews();

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
}
