package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Builds preview items for GitHub comment drafts.
 */
public class GithubCommentPreviewItemBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";
    private static final String FEEDBACK_VALID = "VALID";
    private static final String FEEDBACK_FALSE_POSITIVE = "FALSE_POSITIVE";
    private static final String FEEDBACK_FIXED = "FIXED";
    private static final String FEEDBACK_IGNORED = "IGNORED";

    public GithubCommentPreviewItem buildFindingItem(
        ReviewFinding finding,
        ChangedFile changedFile,
        GithubCommentPublication publication
    ) {
        String targetType = resolveCommentTargetType(finding, changedFile);
        String reason = resolveCommentReason(targetType, finding, changedFile);
        boolean published = isPublished(publication);
        boolean actionable = isActionableFinding(finding);
        return new GithubCommentPreviewItem(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            buildGithubCommentBody(finding),
            !published && actionable,
            targetType,
            published ? "GitHub comment already published" : actionable ? reason : feedbackSkipReason(finding),
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER),
            lower(resolveFindingFeedbackStatus(finding))
        );
    }

    public GithubCommentPreviewItem buildPrSummaryItem(
        PrReviewSummaryDto summary,
        GithubCommentPublication publication
    ) {
        boolean published = isPublished(publication);
        return new GithubCommentPreviewItem(
            null,
            summary.overallRisk(),
            "PR 总评",
            null,
            summary.summary(),
            summary.mergeRecommendation(),
            summary.githubCommentBody(),
            !published,
            "pull_request",
            published ? "GitHub comment already published" : null,
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER),
            "valid"
        );
    }

    private boolean isPublished(GithubCommentPublication publication) {
        return publication != null
            && Boolean.TRUE.equals(publication.getSuccess())
            && StringUtils.hasText(publication.getGithubUrl());
    }

    private String resolveCommentTargetType(ReviewFinding finding, ChangedFile changedFile) {
        if (
            StringUtils.hasText(finding.getFilePath())
                && finding.getLineNumber() != null
                && finding.getLineNumber() > 0
                && changedFile != null
                && !isDeletedChange(changedFile.getChangeType())
        ) {
            return "line";
        }
        return "pull_request";
    }

    private String resolveCommentReason(String targetType, ReviewFinding finding, ChangedFile changedFile) {
        if ("line".equals(targetType)) {
            return null;
        }
        if (!StringUtils.hasText(finding.getFilePath())) {
            return "Finding is missing file path and will be posted as a PR comment";
        }
        if (finding.getLineNumber() == null || finding.getLineNumber() <= 0) {
            return "Finding is missing a valid line number and will be posted as a PR comment";
        }
        if (changedFile == null) {
            return "Finding file is not in the changed files list and will be posted as a PR comment";
        }
        if (isDeletedChange(changedFile.getChangeType())) {
            return "Deleted files will be posted as PR comments";
        }
        return "Finding will be posted as a PR comment";
    }

    private boolean isDeletedChange(String changeType) {
        if (!StringUtils.hasText(changeType)) {
            return false;
        }
        return Set.of("D", "DELETE", "DELETED", "REMOVE", "REMOVED").contains(changeType.trim().toUpperCase(Locale.ROOT));
    }

    private String buildGithubCommentBody(ReviewFinding finding) {
        StringBuilder body = new StringBuilder();
        body.append("**RepoGuard ");
        if (StringUtils.hasText(finding.getSeverity())) {
            body.append(finding.getSeverity().trim().toUpperCase(Locale.ROOT));
        } else {
            body.append("INFO");
        }
        body.append(" finding**");

        if (StringUtils.hasText(finding.getRuleId())) {
            body.append(" · `").append(finding.getRuleId().trim()).append("`");
        }
        if (StringUtils.hasText(finding.getMessage())) {
            body.append("\n\n").append(finding.getMessage().trim());
        }
        if (StringUtils.hasText(finding.getRecommendation())) {
            body.append("\n\n**建议**：").append(finding.getRecommendation().trim());
        }
        return body.toString();
    }

    private boolean isActionableFinding(ReviewFinding finding) {
        String feedbackStatus = resolveFindingFeedbackStatus(finding);
        return FEEDBACK_UNREVIEWED.equals(feedbackStatus) || FEEDBACK_VALID.equals(feedbackStatus);
    }

    private String feedbackSkipReason(ReviewFinding finding) {
        return switch (resolveFindingFeedbackStatus(finding)) {
            case FEEDBACK_FALSE_POSITIVE -> "Finding marked as false positive and will not be published";
            case FEEDBACK_FIXED -> "Finding marked as fixed and will not be published";
            case FEEDBACK_IGNORED -> "Finding marked as ignored and will not be published";
            default -> "Finding is not actionable and will not be published";
        };
    }

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
