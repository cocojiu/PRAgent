package com.repoguard.agent.review.config;

import com.repoguard.agent.entity.ReviewRuleConfig;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleConfigPolicy {

    public String normalizeRuleId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeSeverity(String value) {
        return value == null ? "INFO" : value.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeStatus(String value) {
        return value == null ? "DISABLED" : value.trim().toUpperCase(Locale.ROOT);
    }

    public int nextSortOrder(List<ReviewRuleConfig> rules) {
        return rules == null || rules.isEmpty() || rules.getFirst().getSortOrder() == null
            ? 10
            : rules.getFirst().getSortOrder() + 10;
    }
}
