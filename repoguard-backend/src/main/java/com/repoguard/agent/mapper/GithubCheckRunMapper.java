package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.GithubCheckRun;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GithubCheckRunMapper extends BaseMapper<GithubCheckRun> {

    @Select("""
        select * from github_check_run
        where task_id = #{taskId}
        order by run_sequence desc
        limit 1
        """)
    GithubCheckRun selectLatestForTask(@Param("taskId") Long taskId);

    @Select("""
        select * from github_check_run
        where github_check_run_id = #{githubCheckRunId}
        limit 1
        """)
    GithubCheckRun selectByGithubCheckRunId(@Param("githubCheckRunId") Long githubCheckRunId);

    @Select("""
        select * from github_check_run
        where external_id = #{externalId}
        limit 1
        """)
    GithubCheckRun selectByExternalId(@Param("externalId") String externalId);

    @Select("""
        select * from github_check_run
        where (applied_stage is null or applied_stage <> desired_stage or applied_version < desired_version)
          and (next_dispatch_at is null or next_dispatch_at <= #{now})
          and (claimed_at is null or claimed_at <= #{expiredBefore})
        order by updated_at asc, id asc
        limit #{limit}
        """)
    List<GithubCheckRun> selectDue(
        @Param("now") LocalDateTime now,
        @Param("expiredBefore") LocalDateTime expiredBefore,
        @Param("limit") int limit
    );

    @Update("""
        update github_check_run
           set claimed_at = #{claimedAt}, claimed_by = #{claimedBy}, updated_at = #{claimedAt}
         where id = #{id}
           and (claimed_at is null or claimed_at <= #{expiredBefore})
           and (next_dispatch_at is null or next_dispatch_at <= #{claimedAt})
        """)
    int claim(
        @Param("id") Long id,
        @Param("claimedAt") LocalDateTime claimedAt,
        @Param("expiredBefore") LocalDateTime expiredBefore,
        @Param("claimedBy") String claimedBy
    );

    @Update("""
        update github_check_run
           set claimed_at = null, claimed_by = null, updated_at = #{updatedAt}
         where id = #{id} and claimed_by = #{claimedBy}
        """)
    int release(@Param("id") Long id, @Param("claimedBy") String claimedBy, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        update github_check_run
           set github_check_run_id = #{githubCheckRunId}, applied_stage = #{appliedStage},
               updated_at = #{updatedAt}, last_error = null
         where id = #{id} and claimed_by = #{claimedBy}
        """)
    int markCreated(
        @Param("id") Long id,
        @Param("claimedBy") String claimedBy,
        @Param("githubCheckRunId") Long githubCheckRunId,
        @Param("appliedStage") String appliedStage,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
        update github_check_run
           set applied_stage = #{appliedStage}, applied_version = #{appliedVersion},
               updated_at = #{updatedAt}, last_error = null
         where id = #{id} and claimed_by = #{claimedBy}
        """)
    int markApplied(
        @Param("id") Long id,
        @Param("claimedBy") String claimedBy,
        @Param("appliedStage") String appliedStage,
        @Param("appliedVersion") Long appliedVersion,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
        update github_check_run
           set dispatch_attempts = coalesce(dispatch_attempts, 0) + 1,
               next_dispatch_at = #{nextDispatchAt}, last_error = #{lastError},
               claimed_at = null, claimed_by = null, updated_at = #{updatedAt}
         where id = #{id} and claimed_by = #{claimedBy}
        """)
    int markFailed(
        @Param("id") Long id,
        @Param("claimedBy") String claimedBy,
        @Param("nextDispatchAt") LocalDateTime nextDispatchAt,
        @Param("lastError") String lastError,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
