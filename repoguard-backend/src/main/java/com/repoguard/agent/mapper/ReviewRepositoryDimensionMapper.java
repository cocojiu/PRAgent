package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewRepositoryDimension;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewRepositoryDimensionMapper extends BaseMapper<ReviewRepositoryDimension> {

    @Insert("""
        insert into review_repository_dimension (
            organization, repository, repository_label,
            first_seen_at, last_seen_at, task_count, active,
            created_at, updated_at
        ) values (
            #{organization}, #{repository}, concat(#{organization}, '/', #{repository}),
            #{seenAt}, #{seenAt}, 1, 1,
            #{seenAt}, #{seenAt}
        )
        on duplicate key update
            repository_label = values(repository_label),
            last_seen_at = greatest(last_seen_at, values(last_seen_at)),
            task_count = task_count + 1,
            active = 1,
            updated_at = values(updated_at)
        """)
    int upsertRepository(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("seenAt") LocalDateTime seenAt
    );

    @Select("""
        select repository_label
        from review_repository_dimension
        where active = 1
        order by repository_label asc
        """)
    List<String> selectActiveRepositoryLabels();
}
