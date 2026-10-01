package com.repoguard.agent.mapper;

import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GithubCommentFindingQueries {
    // Match FindingFeedbackStatus.fromStored: Java whitespace defaults to UNREVIEWED,
    // trim removes only U+0000..U+0020, and unknown/accented stored values stay excluded.
    String PERSISTING_COMMENTABLE_WHERE = """
        where task_id = #{taskId} and current_attempt = 1
          and category = 'FINDING' and comparison_status = 'PERSISTING'
          and cast(case
            when feedback_status is null or regexp_like(feedback_status,
              '^[\\\\x{0009}-\\\\x{000D}\\\\x{001C}-\\\\x{0020}\\\\x{1680}\\\\x{2000}-\\\\x{2006}\\\\x{2008}-\\\\x{200A}\\\\x{2028}\\\\x{2029}\\\\x{205F}\\\\x{3000}]*$')
            then 'UNREVIEWED'
            else upper(regexp_replace(feedback_status, '^[\\\\x{0000}-\\\\x{0020}]+|[\\\\x{0000}-\\\\x{0020}]+$', ''))
          end as binary) in ('UNREVIEWED', 'VALID')
          and cast(coalesce(upper(enforcement_mode), '') as binary) <> 'OBSERVE'
        """;

    @Select("select count(*) from review_finding " + PERSISTING_COMMENTABLE_WHERE)
    long countCommentablePersistingFindings(@Param("taskId") Long taskId);

    @Select("select id, file_path, line_number, message from review_finding " + PERSISTING_COMMENTABLE_WHERE
        + " order by id asc limit 20")
    List<ReviewFinding> selectCommentablePersistingSummary(@Param("taskId") Long taskId);

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
          and finding.id > #{afterFindingId}
          and not exists (
              select 1
              from github_comment_publication publication
              where publication.task_id = finding.task_id
                and publication.finding_id = finding.id
                and publication.published_success = 1
          )
          and (
              finding.feedback_status_norm in ('UNREVIEWED', 'VALID')
          )
          and upper(coalesce(finding.comparison_status, 'UNMATCHED')) in ('NEW', 'REGRESSED')
          and finding.enforcement_mode <> 'OBSERVE'
        order by finding.id asc
        limit #{limit}
        """)
    List<ReviewFinding> selectGithubCommentPublishCandidatesAfterId(
        @Param("taskId") Long taskId,
        @Param("afterFindingId") long afterFindingId,
        @Param("limit") int limit
    );
}
