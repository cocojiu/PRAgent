package com.repoguard.agent.github.checks;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.ReviewTaskStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubCheckRunOutcomeResolver {

    private final ReviewFindingMapper findingMapper;
    private final GithubCheckRunProperties properties;

    public GithubCheckRunOutcomeResolver(
        ReviewFindingMapper findingMapper,
        GithubCheckRunProperties properties
    ) {
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public Outcome resolve(ReviewTask task) {
        ReviewTaskStatus status = ReviewTaskStatus.from(task.getStatus());
        List<ReviewFinding> blockers = findingMapper.selectGithubCheckRunBlockingFindings(task.getId());
        String conclusion;
        if (status == ReviewTaskStatus.PENDING_HUMAN_REVIEW
            || HumanReviewStatus.PENDING.code().equalsIgnoreCase(task.getHumanReviewStatus())) {
            conclusion = "action_required";
        } else if (status == ReviewTaskStatus.SUPERSEDED) {
            conclusion = "cancelled";
        } else if (status == ReviewTaskStatus.FAILED
            || status == ReviewTaskStatus.REJECTED
            || status == ReviewTaskStatus.CHANGES_REQUESTED) {
            conclusion = "failure";
        } else if ((status == ReviewTaskStatus.COMPLETED || status == ReviewTaskStatus.APPROVED)
            && !blockers.isEmpty()) {
            conclusion = "failure";
        } else if (status == ReviewTaskStatus.COMPLETED || status == ReviewTaskStatus.APPROVED) {
            conclusion = "success";
        } else {
            conclusion = "neutral";
        }
        List<GithubCheckRunGateway.Annotation> annotations = blockers.stream()
            .map(this::annotation)
            .filter(Objects::nonNull)
            .limit(properties.getAnnotationLimit())
            .toList();
        String summary = summary(status, conclusion, blockers.size(), annotations.size());
        return new Outcome(conclusion, summary, blockers.size(), annotations);
    }

    private GithubCheckRunGateway.Annotation annotation(ReviewFinding finding) {
        if (!StringUtils.hasText(finding.getFilePath()) || finding.getLineNumber() == null
            || finding.getLineNumber() < 1) {
            return null;
        }
        String message = clean(finding.getMessage(), "Blocking review finding");
        String title = clean(finding.getRuleId(), "RepoGuard blocking finding");
        String details = clean(finding.getRecommendation(), null);
        int line = Math.min(finding.getLineNumber(), 2_147_483_646);
        return new GithubCheckRunGateway.Annotation(
            finding.getFilePath().trim(), line, line, "failure", message, title, details
        );
    }

    private String summary(ReviewTaskStatus status, String conclusion, int blockers, int annotations) {
        String result = switch (conclusion) {
            case "success" -> "审查通过，未发现阻断合并的问题。";
            case "failure" -> "审查阻断合并：发现 " + blockers + " 个 BLOCK 问题。";
            case "action_required" -> "审查需要人工复核后才能决定是否合并。";
            case "cancelled" -> "本次审查因 Pull Request 版本变化而取消。";
            default -> "审查结果暂不可用，请稍后重试。";
        };
        if (blockers > annotations) {
            result += "其中 " + (blockers - annotations) + " 个问题缺少有效代码定位，详见 RepoGuard。";
        }
        return result;
    }

    private String clean(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().replaceAll("[\\r\\n]+", " ");
        return normalized.length() > 500 ? normalized.substring(0, 497) + "..." : normalized;
    }

    public record Outcome(String conclusion, String summary, int blockerCount, List<GithubCheckRunGateway.Annotation> annotations) {
        public Outcome {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
    }
}
