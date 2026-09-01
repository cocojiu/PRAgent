package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.GithubCommentPreviewFindingStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.ReviewTaskDetailSummary;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleFeedbackStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.RuleHitCount;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.SeverityCounts;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReviewFindingMapper extends BaseMapper<ReviewFinding> {

    @Update("""
        update review_finding
        set current_attempt = 0
        where task_id = #{taskId}
          and current_attempt = 1
        """)
    int markCurrentAttemptHistorical(Long taskId);

    @Select("""
        select rule_id as ruleId, count(*) as total
        from review_finding
        where category = 'FINDING'
          and current_attempt = 1
          and rule_id is not null
          and trim(rule_id) <> ''
        group by rule_id
        """)
    List<RuleHitCount> selectReviewRuleHitCounts();

    @Select("""
        select
            count(*) as totalHits,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED')
                then 1 else 0 end) as validCount,
            sum(case
                when feedback_status_norm = 'FALSE_POSITIVE'
                then 1 else 0 end) as falsePositiveCount,
            sum(case
                when feedback_status_norm in ('VALID', 'FIXED', 'FALSE_POSITIVE')
                then 1 else 0 end) as reviewedCount
        from review_finding
        where category = 'FINDING'
          and current_attempt = 1
        """)
    RuleFeedbackStat selectReviewRuleFeedbackStat();

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
          and current_attempt = 1
          and category = 'FINDING'
          and feedback_status_norm <> 'FALSE_POSITIVE'
          and enforcement_mode <> 'OBSERVE'
        """)
    SeverityCounts selectFindingSeverityCounts(Long taskId);

    @Select("""
        select
            (
                select count(*)
                from changed_file changed
                where changed.task_id = #{taskId}
                  and changed.current_attempt = 1
            ) as changedFileTotal,
            sum(case
                when finding.category = 'FINDING'
                then 1 else 0 end) as findingTotal,
            sum(case
                when finding.category = 'MISSING_TEST'
                then 1 else 0 end) as missingTestTotal,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'critical'
                then 1 else 0 end) as critical,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'high'
                then 1 else 0 end) as high,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'medium'
                then 1 else 0 end) as medium,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm = 'low'
                then 1 else 0 end) as low,
            sum(case
                when finding.category = 'FINDING'
                  and finding.feedback_status_norm <> 'FALSE_POSITIVE'
                  and finding.enforcement_mode <> 'OBSERVE'
                  and finding.severity_norm not in ('critical', 'high', 'medium', 'low')
                then 1 else 0 end) as info
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
        """)
    ReviewTaskDetailSummary selectReviewTaskDetailSummary(Long taskId);

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
                and finding.enforcement_mode <> 'OBSERVE'
                then 1 else 0 end
            ) as commentableFindings
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
          and finding.category = 'FINDING'
        """)
    GithubCommentPreviewFindingStat selectGithubCommentPreviewFindingStat(Long taskId);

    @Select("""
        select *
        from review_finding finding
        where finding.task_id = #{taskId}
          and finding.current_attempt = 1
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
          and finding.current_attempt = 1
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
          and finding.enforcement_mode <> 'OBSERVE'
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
          and finding.enforcement_mode <> 'OBSERVE'
        order by finding.id asc
        limit #{limit}
        """)
    List<ReviewFinding> selectGithubCommentPublishCandidatesAfterId(
        @Param("taskId") Long taskId,
        @Param("afterFindingId") long afterFindingId,
        @Param("limit") int limit
    );

    @Select("""
        select *
        from review_finding
        where task_id = #{taskId}
          and current_attempt = 1
          and category = 'FINDING'
          and coalesce(feedback_status_norm, 'UNREVIEWED') <> 'FALSE_POSITIVE'
          and is_blocking = 1
          and upper(enforcement_mode) = 'BLOCK'
        order by id asc
        """)
    List<ReviewFinding> selectGithubCheckRunBlockingFindings(@Param("taskId") Long taskId);
}
