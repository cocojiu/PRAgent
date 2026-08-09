package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import org.apache.ibatis.annotations.Select;

public interface ReviewStrategyPolicySnapshotMapper extends BaseMapper<ReviewStrategyPolicySnapshot> {

    @Select("""
        select *
        from review_strategy_policy_snapshot
        where active = true
        order by id desc
        limit 1
        for update
        """)
    ReviewStrategyPolicySnapshot selectActiveForUpdate();
}
