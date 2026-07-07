package com.repoguard.agent.review;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LlmReviewParseFailureSummarizer {

    private static final int MAX_REASON_LENGTH = 240;

    String summarize(String content, Exception ex) {
        int length = content == null ? 0 : content.length();
        String reason = ex == null ? "" : ex.getMessage();
        if (!StringUtils.hasText(reason)) {
            reason = ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        return "length=" + length + ", reason=" + truncate(SensitiveTextSanitizer.sanitize(reason));
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH) + "...";
    }
}
