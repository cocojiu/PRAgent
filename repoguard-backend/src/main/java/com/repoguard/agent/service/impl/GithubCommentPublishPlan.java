package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import java.util.List;

record GithubCommentPublishPlan(
    List<GithubReviewCommentDraft> drafts,
    List<GithubCommentPublishItem> skippedItems
) {
}
