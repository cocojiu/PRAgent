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
                when feedback_status_norm = 'VALID'
                then 1 else 0 end) as validCount,
            sum(case
                when feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when feedback_status_norm <> 'UNREVIEWED'
                then 1 else 0 end) as reviewedCount
        from review_finding
        where category = 'FINDING'
        """)
    ReviewRuleFeedbackStat selectReviewRuleFeedbackStat();

    @Select("""
        select
            sum(case
                when severity_norm = 'critical'
                then 1 else 0 end) as critical,
            sum(case
                when severity_norm = 'high'
                then 1 else 0 end) as high,
            sum(case
                when severity_norm = 'medium'
                then 1 else 0 end) as medium,
            sum(case
                when severity_norm = 'low'
                then 1 else 0 end) as low,
            sum(case
                when severity_norm not in ('critical', 'high', 'medium', 'low')
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
                      and publication.published_success = 1
                )
                then 1 else 0 end
            ) as publishedFindings,
            sum(case
                when not exists (
                    select 1
                    from github_comment_publication publication
                    where publication.task_id = finding.task_id
                      and publication.finding_id = finding.id
                      and publication.published_success = 1
                )
                and (
                    finding.feedback_status_norm in ('UNREVIEWED', 'VALID')
                )
                then 1 else 0 end
            ) as commentableFindings
        from review_finding finding force index (idx_review_finding_task_category_id)
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
        """)
    GithubCommentPreviewFindingStat selectGithubCommentPreviewFindingStat(Long taskId);

    @Select("""
        select *
        from review_finding finding force index (idx_review_finding_task_category_id)
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
        from review_finding finding force index (idx_review_finding_task_category_id)
        where finding.task_id = #{taskId}
          and finding.category = 'FINDING'
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
        from review_finding finding force index (idx_review_finding_task_category_id)
        where finding.task_id = #{taskId}
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
        order by finding.id asc
        limit #{limit}
        """)
    List<ReviewFinding> selectGithubCommentPublishCandidatesAfterId(
        @Param("taskId") Long taskId,
        @Param("afterFindingId") long afterFindingId,
        @Param("limit") int limit
    );
}
