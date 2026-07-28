package com.repoguard.agent.github.comment;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.github.GithubCommentTargetType;
import com.repoguard.agent.review.FindingFeedbackStatus;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds preview items for GitHub comment drafts.
 */
@Component
public class GithubCommentPreviewItemBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public GithubCommentPreviewItem buildFindingItem(
        ReviewFinding finding,
        ChangedFile changedFile,
        GithubCommentPublication publication
    ) {
        GithubCommentTargetType targetType = resolveCommentTargetType(finding, changedFile);
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
            targetType.code(),
            published ? "GitHub comment already published" : actionable ? reason : feedbackSkipReason(finding),
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER),
            FindingFeedbackStatus.fromFinding(finding).dtoCode()
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
            GithubCommentTargetType.PULL_REQUEST.code(),
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

    private GithubCommentTargetType resolveCommentTargetType(ReviewFinding finding, ChangedFile changedFile) {
        if (
            StringUtils.hasText(finding.getFilePath())
                && finding.getLineNumber() != null
                && finding.getLineNumber() > 0
                && changedFile != null
                && !isDeletedChange(changedFile.getChangeType())
        ) {
            return GithubCommentTargetType.LINE;
        }
        return GithubCommentTargetType.PULL_REQUEST;
    }

    private String resolveCommentReason(GithubCommentTargetType targetType, ReviewFinding finding, ChangedFile changedFile) {
        if (targetType.isLine()) {
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
        return FindingFeedbackStatus.fromFinding(finding).commentable();
    }

    private String feedbackSkipReason(ReviewFinding finding) {
        return switch (FindingFeedbackStatus.fromFinding(finding)) {
            case FALSE_POSITIVE -> "Finding marked as false positive and will not be published";
            case FIXED -> "Finding marked as fixed and will not be published";
            case IGNORED -> "Finding marked as ignored and will not be published";
            default -> "Finding is not actionable and will not be published";
        };
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
