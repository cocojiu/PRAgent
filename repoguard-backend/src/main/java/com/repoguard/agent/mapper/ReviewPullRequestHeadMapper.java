package com.repoguard.agent.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewPullRequestHeadMapper {

    @Insert("""
        insert into review_pull_request_head (
            organization, repository, pr_number, latest_commit_sha, generation, updated_at, head_updated_at
        ) values (
            #{organization}, #{repository}, #{prNumber}, #{commitSha}, 1, #{updatedAt}, #{headUpdatedAt}
        )
        on duplicate key update
            generation = if(
                head_updated_at is null or values(head_updated_at) >= head_updated_at,
                if(latest_commit_sha = values(latest_commit_sha), generation, generation + 1),
                generation
            ),
            latest_commit_sha = if(
                head_updated_at is null or values(head_updated_at) >= head_updated_at,
                values(latest_commit_sha),
                latest_commit_sha
            ),
            updated_at = if(
                head_updated_at is null or values(head_updated_at) >= head_updated_at,
                values(updated_at),
                updated_at
            ),
            head_updated_at = if(
                head_updated_at is null or values(head_updated_at) >= head_updated_at,
                values(head_updated_at),
                head_updated_at
            )
        """)
    int advance(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber,
        @Param("commitSha") String commitSha,
        @Param("updatedAt") LocalDateTime updatedAt,
        @Param("headUpdatedAt") LocalDateTime headUpdatedAt
    );

    @Select("""
        select latest_commit_sha
        from review_pull_request_head
        where organization = #{organization}
          and repository = #{repository}
          and pr_number = #{prNumber}
        """)
    String selectLatestCommitSha(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber
    );

    @Select("""
        select generation
        from review_pull_request_head
        where organization = #{organization}
          and repository = #{repository}
          and pr_number = #{prNumber}
        """)
    Long selectGeneration(
        @Param("organization") String organization,
        @Param("repository") String repository,
        @Param("prNumber") Integer prNumber
    );
}
