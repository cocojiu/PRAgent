package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.GithubCheckRunPolicy;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GithubCheckRunPolicyMapper extends BaseMapper<GithubCheckRunPolicy> {

    @Select("""
        select * from github_check_run_policy
         where lower(organization) = lower(#{organization})
           and lower(repository) = lower(#{repository})
         limit 1
        """)
    GithubCheckRunPolicy selectByRepository(
        @Param("organization") String organization,
        @Param("repository") String repository
    );

    @Update("""
        update github_check_run_policy
           set enabled = #{enabled}, policy_version = policy_version + 1,
               updated_by = #{updatedBy}, updated_at = #{updatedAt}
         where id = #{id} and policy_version = #{expectedVersion}
        """)
    int updateEnabled(
        @Param("id") Long id,
        @Param("enabled") boolean enabled,
        @Param("expectedVersion") long expectedVersion,
        @Param("updatedBy") String updatedBy,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
