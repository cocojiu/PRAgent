package com.repoguard.agent.review;

import org.springframework.util.StringUtils;

class LlmReviewParseFailureSummarizer {

    String summarize(String content, Exception ex) {
        int length = content == null ? 0 : content.length();
        String reason = ex == null ? "" : ex.getMessage();
        if (!StringUtils.hasText(reason)) {
            reason = ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        return "length=" + length + ", reason=" + reason.replaceAll("\\s+", " ").trim();
    }
}
