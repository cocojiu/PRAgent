package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentWritebackCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentPublishPlanBuilderTest {

    private final GithubCommentPublishPlanBuilder builder = new GithubCommentPublishPlanBuilder();

    @Test
    void buildSeparatesDraftsAndSkippedItems() {
        GithubCommentPublishPlan plan = builder.build(preview(List.of(
            item(1L, "README.md", 8, "line", true, false, null),
            item(2L, "README.md", 9, "line", false, true, "GitHub comment already published"),
            item(3L, "README.md", null, "pull_request", false, false, "Finding marked as ignored and will not be published")
        )));

        assertThat(plan.drafts()).hasSize(1);
        assertThat(plan.drafts().getFirst().findingId()).isEqualTo(1L);
        assertThat(plan.drafts().getFirst().path()).isEqualTo("README.md");
        assertThat(plan.drafts().getFirst().line()).isEqualTo(8);
        assertThat(plan.drafts().getFirst().targetType()).isEqualTo("line");

        assertThat(plan.skippedItems()).hasSize(2);
        assertThat(plan.skippedItems()).extracting("status")
            .containsExactly("already_published", "skipped");
        assertThat(plan.skippedItems().getFirst().success()).isTrue();
        assertThat(plan.skippedItems().getFirst().url()).isEqualTo("https://github.com/comment/already");
        assertThat(plan.skippedItems().getLast().success()).isFalse();
        assertThat(plan.skippedItems().getLast().message())
            .isEqualTo("Finding marked as ignored and will not be published");
    }

    private GithubCommentPreviewResponse preview(List<GithubCommentPreviewItem> items) {
        return new GithubCommentPreviewResponse(
            521L,
            1,
            "https://github.com/octocat/Hello-World/pull/1",
            new GithubCommentWritebackCheck(
                "ready",
                "success",
                "octocat",
                "Hello-World",
                "octocat",
                "Hello-World",
                true,
                true,
                true,
                null,
                List.of()
            ),
            items.size(),
            (int) items.stream().filter(GithubCommentPreviewItem::commentable).count(),
            (int) items.stream().filter(item -> !item.commentable()).count(),
            items
        );
    }

    private GithubCommentPreviewItem item(
        Long findingId,
        String file,
        Integer line,
        String targetType,
        boolean commentable,
        boolean published,
        String reason
    ) {
        return new GithubCommentPreviewItem(
            findingId,
            "low",
            file,
            line,
            "message",
            "recommendation",
            "body",
            commentable,
            targetType,
            reason,
            published,
            published ? "published" : null,
            published ? "https://github.com/comment/already" : null,
            published ? "GitHub comment published" : null,
            published ? "2026-06-18 11:00:00" : null,
            "valid"
        );
    }
}
