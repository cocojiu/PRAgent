package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
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

    @Select("""
        select date_format(created_at, '%m-%d') as dayLabel, count(*) as total
        from review_task
        group by date_format(created_at, '%m-%d')
        order by dayLabel
        """)
    List<DashboardReviewTrendCount> selectReviewTrendCounts();
}
