package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
