package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskListQueryBuilder {

    private static final int MIN_TEXT_KEYWORD_LENGTH = 3;
    private static final int MIN_COMMIT_PREFIX_LENGTH = 7;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter CURSOR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public LambdaQueryWrapper<ReviewTask> build(ReviewQuery query) {
        ReviewTaskListQueryCriteria criteria = normalize(query);
        return filteredQuery(criteria, true);
    }

    public LambdaQueryWrapper<ReviewTask> buildKeysetPage(ReviewQuery query) {
        ReviewTaskListQueryCriteria criteria = normalize(query);
        LambdaQueryWrapper<ReviewTask> wrapper = filteredQuery(criteria, false);
        if (criteria.hasCursor()) {
            wrapper.and(cursor -> cursor
                .lt(ReviewTask::getCreatedAt, criteria.cursorCreatedAt())
                .or(equalCreatedAt -> equalCreatedAt
                    .eq(ReviewTask::getCreatedAt, criteria.cursorCreatedAt())
                    .lt(ReviewTask::getId, criteria.cursorId())
                )
            );
        }
        applyStableListOrder(wrapper);
        return wrapper.last("limit " + boundedPageSize(query.pageSize()));
    }

    public LambdaQueryWrapper<ReviewTask> buildCountQuery(ReviewQuery query) {
        return filteredQuery(normalize(query), false);
    }

    public boolean hasKeysetCursor(ReviewQuery query) {
        return normalize(query).hasCursor();
    }

    private LambdaQueryWrapper<ReviewTask> filteredQuery(ReviewTaskListQueryCriteria criteria, boolean ordered) {
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(criteria.repository())) {
            wrapper.eq(ReviewTask::getRepository, criteria.repository());
        }
        if (StringUtils.hasText(criteria.status())) {
            wrapper.eq(ReviewTask::getStatus, criteria.status());
        }
        if (StringUtils.hasText(criteria.riskLevel())) {
            wrapper.eq(ReviewTask::getRiskLevel, criteria.riskLevel());
        }
        if (StringUtils.hasText(criteria.source())) {
            wrapper.eq(ReviewTask::getSource, criteria.source());
        }
        if (StringUtils.hasText(criteria.triggerSource())) {
            wrapper.eq(ReviewTask::getTriggerSource, criteria.triggerSource());
        }
        if (criteria.prNumber() != null) {
            wrapper.eq(ReviewTask::getPrNumber, criteria.prNumber());
        } else if (StringUtils.hasText(criteria.commitPrefix())) {
            wrapper.likeRight(ReviewTask::getCommitSha, criteria.commitPrefix());
        } else if (StringUtils.hasText(criteria.textKeyword())) {
            wrapper.and(nested -> nested
                .likeRight(ReviewTask::getTitle, criteria.textKeyword())
                .or()
                .likeRight(ReviewTask::getRepository, criteria.textKeyword())
                .or()
                .likeRight(ReviewTask::getOrganization, criteria.textKeyword())
            );
        }
        if (ordered) {
            applyStableListOrder(wrapper);
        }
        return wrapper;
    }

    private void applyStableListOrder(LambdaQueryWrapper<ReviewTask> wrapper) {
        wrapper.orderByDesc(ReviewTask::getCreatedAt)
            .orderByDesc(ReviewTask::getId);
    }

    ReviewTaskListQueryCriteria normalize(ReviewQuery query) {
        String repository = trimToNull(query.repository());
        String status = upperTrimToNull(query.status());
        String riskLevel = upperTrimToNull(query.riskLevel());
        String source = upperTrimToNull(query.source());
        String triggerSource = upperTrimToNull(query.triggerSource());
        String keyword = trimToNull(query.keyword());
        Integer prNumber = StringUtils.hasText(keyword) ? parseIntegerOrNull(keyword) : null;
        String commitPrefix = prNumber == null && isCommitPrefix(keyword) ? keyword : null;
        String textKeyword = prNumber == null && commitPrefix == null && isUsableTextKeyword(keyword) ? keyword : null;
        Long cursorId = query.cursorId();
        return new ReviewTaskListQueryCriteria(
            repository,
            status,
            riskLevel,
            source,
            triggerSource,
            keyword,
            prNumber,
            commitPrefix,
            textKeyword,
            parseCursorCreatedAt(query.cursorCreatedAt()),
            cursorId == null || cursorId <= 0 ? null : cursorId
        );
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String upperTrimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private Integer parseIntegerOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isCommitPrefix(String value) {
        return StringUtils.hasText(value)
            && value.length() >= MIN_COMMIT_PREFIX_LENGTH
            && value.matches("[0-9a-fA-F]+");
    }

    private boolean isUsableTextKeyword(String value) {
        return StringUtils.hasText(value) && value.length() >= MIN_TEXT_KEYWORD_LENGTH;
    }

    private LocalDateTime parseCursorCreatedAt(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized, CURSOR_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return parseIsoCursorCreatedAt(normalized);
        }
    }

    private LocalDateTime parseIsoCursorCreatedAt(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private int boundedPageSize(int pageSize) {
        return Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
    }

    record ReviewTaskListQueryCriteria(
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword,
        Integer prNumber,
        String commitPrefix,
        String textKeyword,
        LocalDateTime cursorCreatedAt,
        Long cursorId
    ) {
        boolean hasCursor() {
            return cursorCreatedAt != null && cursorId != null;
        }
    }
}
