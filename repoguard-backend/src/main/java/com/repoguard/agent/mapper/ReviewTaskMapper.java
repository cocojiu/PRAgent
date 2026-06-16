package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    @Select("""
        select risk_level as riskLevel, count(*) as total
        from review_task
        group by risk_level
        """)
    List<DashboardRiskLevelCount> selectRiskLevelCounts();
}
