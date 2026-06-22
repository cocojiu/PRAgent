package com.repoguard.agent.review;

import java.util.Optional;

class GithubCommentDirectPublishRule implements ReviewRule {

    static final String RULE_ID = "RG-GH-001";

    private final ReviewFindingFactory findingFactory;

    GithubCommentDirectPublishRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !publishesGithubCommentDirectly(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "HIGH",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增 GitHub 评论发布调用缺少显式幂等边界",
            "请确认发布前经过 preview/publication 检查，并在 finding 级和批次级记录回写结果。"
        ));
    }

    private boolean publishesGithubCommentDirectly(String trimmedLine) {
        return trimmedLine.contains("publishPullRequestComments(")
            || trimmedLine.contains("publishPullRequestComment(")
            || trimmedLine.contains("publishLineComment(")
            || trimmedLine.contains("/pulls/{pullNumber}/comments")
            || trimmedLine.contains("/issues/{pullNumber}/comments");
    }
}
