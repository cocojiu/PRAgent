package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskListQueryBuilder {

    private static final int MIN_TEXT_KEYWORD_LENGTH = 3;
    private static final int MIN_COMMIT_PREFIX_LENGTH = 7;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewTaskCursorCodec cursorCodec;

    public ReviewTaskListQueryBuilder(ReviewTaskCursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    public LambdaQueryWrapper<ReviewTask> build(ReviewQuery query) {
        ReviewTaskListQueryCriteria criteria = normalize(query);
        return filteredQuery(criteria, true);
    }

    public LambdaQueryWrapper<ReviewTask> buildKeysetPage(ReviewQuery query) {
        return buildKeysetPage(query, decodeCursor(query));
    }

    LambdaQueryWrapper<ReviewTask> buildKeysetPage(ReviewQuery query, ReviewTaskCursorCodec.Cursor decodedCursor) {
        ReviewTaskListQueryCriteria criteria = normalize(query, decodedCursor);
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
        return wrapper.last("limit " + keysetFetchSize(query.pageSize()));
    }

    public LambdaQueryWrapper<ReviewTask> buildCountQuery(ReviewQuery query) {
        return filteredQuery(normalize(query), false);
    }

    public boolean hasKeysetCursor(ReviewQuery query) {
        return decodeCursor(query) != null;
    }

    ReviewTaskCursorCodec.Cursor decodeCursor(ReviewQuery query) {
        return cursorCodec.decode(query.cursor(), cursorScope(query));
    }

    String encodeCursor(ReviewQuery query, LocalDateTime createdAt, Long id, long total) {
        return cursorCodec.encode(createdAt, id, total, cursorScope(query));
    }

    private LambdaQueryWrapper<ReviewTask> filteredQuery(ReviewTaskListQueryCriteria criteria, boolean ordered) {
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(criteria.organization())) {
            wrapper.eq(ReviewTask::getOrganization, criteria.organization());
        }
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
        return normalize(query, decodeCursor(query));
    }

    private ReviewTaskListQueryCriteria normalize(ReviewQuery query, ReviewTaskCursorCodec.Cursor cursor) {
        RepositoryFilter repositoryFilter = normalizeRepositoryFilter(query.repository());
        String status = upperTrimToNull(query.status());
        String riskLevel = upperTrimToNull(query.riskLevel());
        String source = upperTrimToNull(query.source());
        String triggerSource = upperTrimToNull(query.triggerSource());
        String keyword = trimToNull(query.keyword());
        Integer prNumber = StringUtils.hasText(keyword) ? parseIntegerOrNull(keyword) : null;
        String commitPrefix = prNumber == null && isCommitPrefix(keyword) ? keyword : null;
        String textKeyword = prNumber == null && commitPrefix == null && isUsableTextKeyword(keyword) ? keyword : null;
        return new ReviewTaskListQueryCriteria(
            repositoryFilter.organization(),
            repositoryFilter.repository(),
            status,
            riskLevel,
            source,
            triggerSource,
            keyword,
            prNumber,
            commitPrefix,
            textKeyword,
            cursor == null ? null : cursor.createdAt(),
            cursor == null ? null : cursor.id()
        );
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String upperTrimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private RepositoryFilter normalizeRepositoryFilter(String value) {
        String repository = trimToNull(value);
        if (repository == null) {
            return new RepositoryFilter(null, null);
        }
        int slashIndex = repository.indexOf('/');
        if (slashIndex <= 0 || slashIndex == repository.length() - 1) {
            return new RepositoryFilter(null, repository);
        }
        String organization = trimToNull(repository.substring(0, slashIndex));
        String repositoryName = trimToNull(repository.substring(slashIndex + 1));
        if (organization == null || repositoryName == null) {
            return new RepositoryFilter(null, repository);
        }
        return new RepositoryFilter(organization, repositoryName);
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

    private int boundedPageSize(int pageSize) {
        return Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
    }

    private int keysetFetchSize(int pageSize) {
        return boundedPageSize(pageSize) + 1;
    }

    private String cursorScope(ReviewQuery query) {
        RepositoryFilter repositoryFilter = normalizeRepositoryFilter(query.repository());
        String payload = lengthPrefixed(repositoryFilter.organization())
            + lengthPrefixed(repositoryFilter.repository())
            + lengthPrefixed(upperTrimToNull(query.status()))
            + lengthPrefixed(upperTrimToNull(query.riskLevel()))
            + lengthPrefixed(upperTrimToNull(query.source()))
            + lengthPrefixed(upperTrimToNull(query.triggerSource()))
            + lengthPrefixed(trimToNull(query.keyword()))
            + lengthPrefixed(Integer.toString(boundedPageSize(query.pageSize())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Review list cursor scope hashing is not available", exception);
        }
    }

    private String lengthPrefixed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    record ReviewTaskListQueryCriteria(
        String organization,
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

    record RepositoryFilter(String organization, String repository) {
    }
}
