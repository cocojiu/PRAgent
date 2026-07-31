package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class GithubCommentDirectPublishRule implements ReviewRule {

    static final String RULE_ID = "RG-GH-001";

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;

    @Autowired
    GithubCommentDirectPublishRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
    }

    GithubCommentDirectPublishRule(RuleMatchFactory matchFactory) {
        this(matchFactory, ReviewFilePolicy.defaults());
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id())
            || filePolicy.nonProduction(context.filePath())
            || filePolicy.approvedGithubPublisher(context.filePath())
            || !publishesGithubCommentDirectly(context.trimmedLine())
            || hasIdempotencyBoundary(context)) {
            return Optional.empty();
        }
        boolean verified = context.contextualEvidenceVerified();
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增 GitHub 评论发布调用缺少显式幂等边界",
            "请确认发布前经过 preview/publication 检查，并在 finding 级和批次级记录回写结果。",
            verified
                ? "调用绕过批准发布网关，完整上下文未发现幂等键、回写记录或 commit fence"
                : "完整文件上下文不可用，仅保留直接评论发布的待确认候选",
            verified
        ));
    }

    private boolean publishesGithubCommentDirectly(String trimmedLine) {
        return trimmedLine.contains("publishPullRequestComments(")
            || trimmedLine.contains("publishPullRequestComment(")
            || trimmedLine.contains("publishLineComment(")
            || trimmedLine.contains("/pulls/{pullNumber}/comments")
            || trimmedLine.contains("/issues/{pullNumber}/comments");
    }

    private boolean hasIdempotencyBoundary(ReviewRuleLineContext context) {
        if (!context.fullContextAvailable()) {
            return false;
        }
        String source = context.changedFileContext().content().toLowerCase(Locale.ROOT);
        return source.contains("publicationstore")
            || source.contains("publicationrecord")
            || source.contains("findpublishedfindingids")
            || source.contains("idempotency")
            || source.contains("commitfence")
            || source.contains("headfence")
            || (source.contains("headsha") && source.contains("preview"));
    }
}
