package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.GithubCommentPreviewFindingStat;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewFindingMapper extends BaseMapper<ReviewFinding> {

    @Select("""
        select rule_id as ruleId, count(*) as total
        from review_finding
        where category = 'FINDING'
          and rule_id is not null
          and trim(rule_id) <> ''
        group by rule_id
        """)
    List<ReviewRuleHitCount> selectReviewRuleHitCounts();

    @Select("""
        select
            count(*) as totalHits,
            sum(case
                when upper(coalesce(nullif(trim(feedback_status), ''), 'UNREVIEWED')) = 'VALID'
                then 1 else 0 end) as validCount,
            sum(case
                when upper(coalesce(nullif(trim(feedback_status), ''), 'UNREVIEWED')) = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when upper(coalesce(nullif(trim(feedback_status), ''), 'UNREVIEWED')) <> 'UNREVIEWED'
                then 1 else 0 end) as reviewedCount
        from review_finding
        where category = 'FINDING'
        """)
    ReviewRuleFeedbackStat selectReviewRuleFeedbackStat();

    @Select("""
        select
            sum(case
                when lower(coalesce(nullif(trim(severity), ''), 'info')) = 'critical'
                then 1 else 0 end) as critical,
            sum(case
                when lower(coalesce(nullif(trim(severity), ''), 'info')) = 'high'
                then 1 else 0 end) as high,
            sum(case
                when lower(coalesce(nullif(trim(severity), ''), 'info')) = 'medium'
                then 1 else 0 end) as medium,
            sum(case
                when lower(coalesce(nullif(trim(severity), ''), 'info')) = 'low'
                then 1 else 0 end) as low,
            sum(case
                when lower(coalesce(nullif(trim(severity), ''), 'info')) not in ('critical', 'high', 'medium', 'low')
                then 1 else 0 end) as info
        from review_finding
        where task_id = #{taskId}
          and category = 'FINDING'
        """)
    FindingSeverityCountsDto selectFindingSeverityCounts(Long taskId);

    @Select("""
        select
            count(*) as totalFindings,
            sum(case
                when exists (
                    select 1
                    from github_comment_publication publication
                    where publication.task_id = finding.task_id
                      and publication.finding_id = finding.id
                      and publication.success = 1
                      and publication.github_url is not null
                      and trim(publication.github_url) <> ''
                )
                then 1 else 0 end
            ) as publishedFindings,
            sum(case
                when not exists (
                    select 1
                    from github_comment_publication publication
                    where publication.task_id = finding.task_id
                      and publication.finding_id = finding.id
                      and publication.success = 1
                      and publication.github_url is not null
                      and trim(publication.github_url) <> ''
                )
                and (
                    upper(coalesce(nullif(trim(finding.feedback_status), ''), 'UNREVIEWED'))
                        in ('UNREVIEWED', 'VALID')
                )
                then 1 else 0 end
            ) as commentableFindings
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
        """)
    GithubCommentPreviewFindingStat selectGithubCommentPreviewFindingStat(Long taskId);

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
        order by finding.id asc
        limit #{limit} offset #{offset}
        """)
    List<ReviewFinding> selectGithubCommentPreviewFindings(
        @Param("taskId") Long taskId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
          and not exists (
              select 1
              from github_comment_publication publication
              where publication.task_id = finding.task_id
                and publication.finding_id = finding.id
                and publication.success = 1
                and publication.github_url is not null
                and trim(publication.github_url) <> ''
          )
          and (
              upper(coalesce(nullif(trim(finding.feedback_status), ''), 'UNREVIEWED'))
                  in ('UNREVIEWED', 'VALID')
          )
        order by finding.id asc
        limit #{limit} offset #{offset}
        """)
    List<ReviewFinding> selectGithubCommentPreviewCommentableFindings(
        @Param("taskId") Long taskId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
          and finding.id > #{afterFindingId}
          and not exists (
              select 1
              from github_comment_publication publication
              where publication.task_id = finding.task_id
                and publication.finding_id = finding.id
                and publication.success = 1
                and publication.github_url is not null
                and trim(publication.github_url) <> ''
          )
          and (
              upper(coalesce(nullif(trim(finding.feedback_status), ''), 'UNREVIEWED'))
                  in ('UNREVIEWED', 'VALID')
          )
        order by finding.id asc
        limit #{limit}
        """)
    List<ReviewFinding> selectGithubCommentPublishCandidatesAfterId(
        @Param("taskId") Long taskId,
        @Param("afterFindingId") long afterFindingId,
        @Param("limit") int limit
    );
}
