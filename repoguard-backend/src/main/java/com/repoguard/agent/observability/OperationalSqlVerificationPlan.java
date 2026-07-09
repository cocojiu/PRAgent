package com.repoguard.agent.observability;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OperationalSqlVerificationPlan {

    public List<QueryAssumption> queryAssumptions() {
        return List.of(
            new QueryAssumption(
                ReviewFindingMapper.class,
                "selectReviewRuleHitCounts",
                List.of(),
                "global rule hit aggregate filters review_finding by category before grouping by rule_id",
                List.of("idx_review_finding_category_rule")
            ),
            new QueryAssumption(
                ReviewFindingMapper.class,
                "selectReviewRuleFeedbackStat",
                List.of(),
                "global rule feedback aggregate filters review_finding by category and reads generated feedback status",
                List.of("idx_review_finding_category_feedback_norm")
            ),
            new QueryAssumption(
                ReviewFindingMapper.class,
                "selectFindingSeverityCounts",
                List.of(Long.class),
                "task detail severity aggregate enters by task_id/category and groups generated severity",
                List.of("idx_review_finding_task_category_severity_norm")
            ),
            new QueryAssumption(
                ReviewFindingMapper.class,
                "selectGithubCommentPreviewCommentableFindings",
                List.of(Long.class, long.class, int.class),
                "preview commentable findings use keyset id paging and published-success anti-join",
                List.of(
                    "idx_review_finding_task_category_id",
                    "idx_github_comment_publication_task_finding_published",
                    "idx_review_finding_task_category_feedback_norm"
                )
            ),
            new QueryAssumption(
                ReviewFindingMapper.class,
                "selectGithubCommentPublishCandidatesAfterId",
                List.of(Long.class, long.class, int.class),
                "publish candidates use keyset id paging and published-success anti-join",
                List.of(
                    "idx_review_finding_task_category_id",
                    "idx_github_comment_publication_task_finding_published",
                    "idx_review_finding_task_category_feedback_norm"
                )
            ),
            new QueryAssumption(
                ChangedFileMapper.class,
                "selectChangedFilesWithFindings",
                List.of(Page.class, Long.class),
                "changed files with findings use task-scoped exists lookup by file_path",
                List.of("idx_changed_file_task_file", "idx_review_finding_task_category_file")
            ),
            new QueryAssumption(
                ChangedFileMapper.class,
                "selectChangedFilesWithoutFindings",
                List.of(Page.class, Long.class),
                "changed files without findings use task-scoped anti-exists lookup by file_path",
                List.of("idx_changed_file_task_file", "idx_review_finding_task_category_file")
            ),
            new QueryAssumption(
                ReviewTaskMapper.class,
                "selectMessageQueueExceptionTasks",
                List.of(),
                "message queue exception list filters generated status before ordering recent failures",
                List.of("idx_review_task_status_norm_created")
            )
        );
    }

    public List<IndexAlignment> indexAlignments() {
        return List.of(
            new IndexAlignment(
                "idx_review_finding_category_rule",
                List.of("category", "rule_id"),
                "Global rule hit statistics do not constrain task_id, so category must be the leading lookup column."
            ),
            new IndexAlignment(
                "idx_review_finding_category_feedback_norm",
                List.of("category", "feedback_status_norm"),
                "Global feedback statistics do not constrain task_id and should read the generated normalized status."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_severity_norm",
                List.of("task_id", "category", "severity_norm"),
                "Task detail severity counts enter by task_id/category before aggregating generated severity."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_id",
                List.of("task_id", "category", "id"),
                "GitHub comment candidate paging enters by task_id/category and advances by finding id."
            ),
            new IndexAlignment(
                "idx_github_comment_publication_task_finding_published",
                List.of("task_id", "finding_id", "published_success"),
                "Comment publication anti-joins check task/finding pairs and generated published status."
            ),
            new IndexAlignment(
                "idx_review_finding_task_category_file",
                List.of("task_id", "category", "file_path(255)"),
                "Changed-file finding presence checks enter review_finding by task_id/category/file_path."
            ),
            new IndexAlignment(
                "idx_changed_file_task_file",
                List.of("task_id", "file_path(255)"),
                "Changed-file pages filter by task_id and correlate finding subqueries through file_path."
            ),
            new IndexAlignment(
                "idx_review_task_status_norm_created",
                List.of("status_norm", "created_at"),
                "Message queue exception lists filter generated status_norm before recent created_at ordering."
            )
        );
    }

    public List<ExplainObservation> explainObservations() {
        return List.of(
            new ExplainObservation(
                "selectReviewRuleHitCounts",
                List.of("idx_review_finding_category_rule"),
                List.of("ref", "range"),
                "rows should be bounded by category before rule grouping.",
                List.of("key should prefer idx_review_finding_category_rule", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectReviewRuleFeedbackStat",
                List.of("idx_review_finding_category_feedback_norm"),
                List.of("ref", "range"),
                "rows should be bounded by category while feedback_status_norm remains index-covered.",
                List.of("key should prefer idx_review_finding_category_feedback_norm", "type should not degrade to ALL")
            ),
            new ExplainObservation(
                "selectFindingSeverityCounts",
                List.of("idx_review_finding_task_category_severity_norm"),
                List.of("ref", "range"),
                "rows should be bounded by task_id/category before severity aggregation.",
                List.of("key should include task_id/category/severity_norm")
            ),
            new ExplainObservation(
                "selectGithubCommentPreviewCommentableFindings",
                List.of(
                    "idx_review_finding_task_category_id",
                    "idx_github_comment_publication_task_finding_published",
                    "idx_review_finding_task_category_feedback_norm"
                ),
                List.of("range", "ref", "eq_ref"),
                "rows should advance by keyset id and probe publication by task/finding/published status.",
                List.of("finding key should include task_id/category/id", "publication probe should not scan")
            ),
            new ExplainObservation(
                "selectGithubCommentPublishCandidatesAfterId",
                List.of(
                    "idx_review_finding_task_category_id",
                    "idx_github_comment_publication_task_finding_published",
                    "idx_review_finding_task_category_feedback_norm"
                ),
                List.of("range", "ref", "eq_ref"),
                "rows should advance by keyset id and probe publication by task/finding/published status.",
                List.of("finding key should include task_id/category/id", "publication probe should not scan")
            ),
            new ExplainObservation(
                "selectChangedFilesWithFindings",
                List.of("idx_changed_file_task_file", "idx_review_finding_task_category_file"),
                List.of("ref", "range", "eq_ref"),
                "rows should stay task-scoped on changed_file and probe review_finding by task/category/path.",
                List.of("subquery should not scan all review_finding rows")
            ),
            new ExplainObservation(
                "selectChangedFilesWithoutFindings",
                List.of("idx_changed_file_task_file", "idx_review_finding_task_category_file"),
                List.of("ref", "range", "eq_ref"),
                "rows should stay task-scoped on changed_file and anti-probe review_finding by task/category/path.",
                List.of("anti-subquery should not scan all review_finding rows")
            ),
            new ExplainObservation(
                "selectMessageQueueExceptionTasks",
                List.of("idx_review_task_status_norm_created"),
                List.of("range", "ref"),
                "rows should be bounded by status_norm before ordering recent exception tasks.",
                List.of("key should use generated status_norm index", "type should not degrade to ALL")
            )
        );
    }

    public List<ExplainTableExpectation> explainTableExpectations() {
        return List.of(
            new ExplainTableExpectation(
                "selectReviewRuleHitCounts",
                "review_finding",
                "",
                List.of("idx_review_finding_category_rule"),
                List.of("ref", "range"),
                "review_finding rows should be category-bounded before grouping by rule_id.",
                List.of("review_finding key should include category/rule_id")
            ),
            new ExplainTableExpectation(
                "selectReviewRuleFeedbackStat",
                "review_finding",
                "",
                List.of("idx_review_finding_category_feedback_norm"),
                List.of("ref", "range"),
                "review_finding rows should be category-bounded before feedback aggregation.",
                List.of("review_finding key should include category/feedback_status_norm")
            ),
            new ExplainTableExpectation(
                "selectFindingSeverityCounts",
                "review_finding",
                "",
                List.of("idx_review_finding_task_category_severity_norm"),
                List.of("ref", "range"),
                "review_finding rows should be task/category-bounded before severity aggregation.",
                List.of("review_finding key should include task_id/category/severity_norm")
            ),
            new ExplainTableExpectation(
                "selectGithubCommentPreviewCommentableFindings",
                "review_finding",
                "finding",
                List.of("idx_review_finding_task_category_id", "idx_review_finding_task_category_feedback_norm"),
                List.of("range", "ref"),
                "finding rows should be task/category/id-bounded before limit.",
                List.of("finding key should not be null")
            ),
            new ExplainTableExpectation(
                "selectGithubCommentPreviewCommentableFindings",
                "github_comment_publication",
                "publication",
                List.of("idx_github_comment_publication_task_finding_published"),
                List.of("ref", "eq_ref"),
                "publication rows should be probed by task/finding/published_success.",
                List.of("publication key should include published_success")
            ),
            new ExplainTableExpectation(
                "selectGithubCommentPublishCandidatesAfterId",
                "review_finding",
                "finding",
                List.of("idx_review_finding_task_category_id", "idx_review_finding_task_category_feedback_norm"),
                List.of("range", "ref"),
                "finding rows should be task/category/id-bounded before limit.",
                List.of("finding key should not be null")
            ),
            new ExplainTableExpectation(
                "selectGithubCommentPublishCandidatesAfterId",
                "github_comment_publication",
                "publication",
                List.of("idx_github_comment_publication_task_finding_published"),
                List.of("ref", "eq_ref"),
                "publication rows should be probed by task/finding/published_success.",
                List.of("publication key should include published_success")
            ),
            new ExplainTableExpectation(
                "selectChangedFilesWithFindings",
                "changed_file",
                "file",
                List.of("idx_changed_file_task_file"),
                List.of("ref", "range"),
                "changed_file rows should be task-bounded before paging.",
                List.of("changed_file key should include task_id")
            ),
            new ExplainTableExpectation(
                "selectChangedFilesWithFindings",
                "review_finding",
                "finding",
                List.of("idx_review_finding_task_category_file"),
                List.of("ref", "eq_ref"),
                "review_finding rows should be probed by task/category/file_path.",
                List.of("review_finding key should include file_path")
            ),
            new ExplainTableExpectation(
                "selectChangedFilesWithoutFindings",
                "changed_file",
                "file",
                List.of("idx_changed_file_task_file"),
                List.of("ref", "range"),
                "changed_file rows should be task-bounded before paging.",
                List.of("changed_file key should include task_id")
            ),
            new ExplainTableExpectation(
                "selectChangedFilesWithoutFindings",
                "review_finding",
                "finding",
                List.of("idx_review_finding_task_category_file"),
                List.of("ref", "eq_ref"),
                "review_finding rows should be anti-probed by task/category/file_path.",
                List.of("review_finding key should include file_path")
            ),
            new ExplainTableExpectation(
                "selectMessageQueueExceptionTasks",
                "review_task",
                "",
                List.of("idx_review_task_status_norm_created"),
                List.of("range", "ref"),
                "review_task rows should be status_norm-bounded before recent ordering.",
                List.of("review_task key should include status_norm/created_at")
            )
        );
    }

    public record QueryAssumption(
        Class<?> mapperClass,
        String mapperMethod,
        List<Class<?>> parameterTypes,
        String verificationScope,
        List<String> supportingIndexes
    ) {
    }

    public record IndexAlignment(
        String indexName,
        List<String> leadingColumns,
        String reason
    ) {
    }

    public record ExplainObservation(
        String mapperMethod,
        List<String> keyCandidates,
        List<String> acceptableAccessTypes,
        String rowsExpectation,
        List<String> extraWatchItems
    ) {
    }

    public record ExplainTableExpectation(
        String mapperMethod,
        String tableName,
        String tableAlias,
        List<String> keyCandidates,
        List<String> acceptableAccessTypes,
        String rowsExpectation,
        List<String> extraWatchItems
    ) {
    }
}
