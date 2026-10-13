package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewTimeline;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewTimelineMapper extends BaseMapper<ReviewTimeline> {
    @Select("""
        <script>
        select task_id, label
        from (
            select task_id, label,
                row_number() over (partition by task_id order by sort_order desc, id desc) as failure_rank
            from review_timeline
            where task_id in
            <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
            </foreach>
              and (cast(label as binary) = 'Review failed' or cast(label as binary) like 'Review failed:%')
        ) failures
        where failure_rank = 1
        </script>
        """)
    List<ReviewTimeline> selectLatestFailures(@Param("taskIds") List<Long> taskIds);
}
