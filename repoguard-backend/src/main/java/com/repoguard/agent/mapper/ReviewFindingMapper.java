package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface ReviewFindingMapper extends BaseMapper<ReviewFinding> {

    @Select("""
        select rule_id as ruleId, count(*) as total
        from review_finding
        where category = 'FINDING'
          and rule_id is not null
          and trim(rule_id) <> ''
        group by rule_id
        """)
    List<ReviewRuleHitCount> selectReviewRuleHitCounts();

    @Select("""
        select
            count(*) as totalHits,
            sum(case when feedback_status = 'VALID' then 1 else 0 end) as validCount,
            sum(case when feedback_status = 'FALSE_POSITIVE' then 1 else 0 end) as falsePositiveCount,
            sum(case
                when feedback_status is not null
                  and feedback_status <> ''
                  and feedback_status <> 'UNREVIEWED'
                then 1 else 0 end) as reviewedCount
        from review_finding
        where category = 'FINDING'
        """)
    ReviewRuleFeedbackStat selectReviewRuleFeedbackStat();

    @Select("""
        select
            sum(case when lower(severity) = 'critical' then 1 else 0 end) as critical,
            sum(case when lower(severity) = 'high' then 1 else 0 end) as high,
            sum(case when lower(severity) = 'medium' then 1 else 0 end) as medium,
            sum(case when lower(severity) = 'low' then 1 else 0 end) as low,
            sum(case
                when severity is null
                  or trim(severity) = ''
                  or lower(severity) not in ('critical', 'high', 'medium', 'low')
                then 1 else 0 end) as info
        from review_finding
        where task_id = #{taskId}
          and category = 'FINDING'
        """)
    FindingSeverityCountsDto selectFindingSeverityCounts(Long taskId);
}
