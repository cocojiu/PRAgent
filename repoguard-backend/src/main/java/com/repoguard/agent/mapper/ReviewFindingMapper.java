package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface ReviewFindingMapper extends BaseMapper<ReviewFinding> {

    @Select("""
        select coalesce(rule_id, 'LLM') as ruleId, count(*) as total
        from review_finding
        where category = 'FINDING'
        group by coalesce(rule_id, 'LLM')
        """)
    List<DashboardRuleHitCount> selectRuleHitCounts();
}
