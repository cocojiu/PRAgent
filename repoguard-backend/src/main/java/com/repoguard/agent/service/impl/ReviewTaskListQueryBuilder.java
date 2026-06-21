package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskListQueryBuilder {

    private static final int MIN_TEXT_KEYWORD_LENGTH = 3;
    private static final int MIN_COMMIT_PREFIX_LENGTH = 7;

    public LambdaQueryWrapper<ReviewTask> build(ReviewQuery query) {
        ReviewTaskListQueryCriteria criteria = normalize(query);
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<ReviewTask>()
            .orderByDesc(ReviewTask::getCreatedAt);

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
                .like(ReviewTask::getTitle, criteria.textKeyword())
                .or()
                .like(ReviewTask::getRepository, criteria.textKeyword())
                .or()
                .like(ReviewTask::getOrganization, criteria.textKeyword())
            );
        }
        return wrapper;
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
        return new ReviewTaskListQueryCriteria(
            repository,
            status,
            riskLevel,
            source,
            triggerSource,
            keyword,
            prNumber,
            commitPrefix,
            textKeyword
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

    record ReviewTaskListQueryCriteria(
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword,
        Integer prNumber,
        String commitPrefix,
        String textKeyword
    ) {
    }
}
