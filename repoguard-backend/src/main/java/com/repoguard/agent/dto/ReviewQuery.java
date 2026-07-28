package com.repoguard.agent.dto;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * Controller 校验后的评审列表查询参数。
 */
public record ReviewQuery(
    int page,
    int pageSize,
    String repository,
    String status,
    String riskLevel,
    String source,
    String triggerSource,
    String keyword,
    String cursorCreatedAt,
    Long cursorId,
    Long totalHint
) {
    public ReviewQuery(
        int page,
        int pageSize,
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword
    ) {
        this(page, pageSize, repository, status, riskLevel, source, triggerSource, keyword, null, null, null);
    }

    public ReviewQuery(
        int page,
        int pageSize,
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword,
        String cursorCreatedAt,
        Long cursorId
    ) {
        this(
            page,
            pageSize,
            repository,
            status,
            riskLevel,
            source,
            triggerSource,
            keyword,
            cursorCreatedAt,
            cursorId,
            null
        );
    }

    public ReviewListSummaryCacheKey listSummaryCacheKey() {
        return new ReviewListSummaryCacheKey(
            trimToNull(repository),
            upperTrimToNull(status),
            upperTrimToNull(riskLevel),
            upperTrimToNull(source),
            upperTrimToNull(triggerSource),
            trimToNull(keyword)
        );
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String upperTrimToNull(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    public record ReviewListSummaryCacheKey(
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword
    ) {
    }
}
