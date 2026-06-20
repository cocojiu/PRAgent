package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskListQueryBuilder {

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
        if (StringUtils.hasText(criteria.keyword())) {
            wrapper.and(nested -> nested
                .like(ReviewTask::getTitle, criteria.keyword())
                .or()
                .like(ReviewTask::getRepository, criteria.keyword())
                .or()
                .like(ReviewTask::getOrganization, criteria.keyword())
                .or()
                .like(ReviewTask::getCommitSha, criteria.keyword())
                .or(criteria.prNumber() != null)
                .eq(criteria.prNumber() != null, ReviewTask::getPrNumber, criteria.prNumber())
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
        return new ReviewTaskListQueryCriteria(
            repository,
            status,
            riskLevel,
            source,
            triggerSource,
            keyword,
            prNumber
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

    record ReviewTaskListQueryCriteria(
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword,
        Integer prNumber
    ) {
    }
}
