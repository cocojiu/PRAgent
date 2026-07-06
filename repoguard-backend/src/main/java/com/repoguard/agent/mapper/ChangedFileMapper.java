package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ChangedFile;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ChangedFileMapper extends BaseMapper<ChangedFile> {

    @Select("""
        select *
        from changed_file
        where task_id = #{taskId}
        order by (coalesce(additions, 0) + coalesce(deletions, 0)) desc, id asc
        limit #{limit}
        """)
    List<ChangedFile> selectTopChangedFilesByChurn(@Param("taskId") Long taskId, @Param("limit") int limit);
}
