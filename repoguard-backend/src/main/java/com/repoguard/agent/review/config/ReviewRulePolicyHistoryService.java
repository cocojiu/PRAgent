package com.repoguard.agent.review.config;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewRulePolicyHistoryService {

    private final ReviewRuleQueryService queryService;
    private final ReviewRulePolicySnapshotStore policySnapshotStore;
    private final ReviewRuleResponseAssembler responseAssembler;

    public ReviewRulePolicyHistoryService(
        ReviewRuleQueryService queryService,
        ReviewRulePolicySnapshotStore policySnapshotStore,
        ReviewRuleResponseAssembler responseAssembler
    ) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.policySnapshotStore = Objects.requireNonNull(policySnapshotStore, "policySnapshotStore");
        this.responseAssembler = Objects.requireNonNull(responseAssembler, "responseAssembler");
    }

    PageResponse<ReviewRulePolicyVersionDto> getVersions(String id, Long cursor, int pageSize) {
        validatePage(cursor, pageSize);
        String normalizedId = queryService.normalizeRegisteredRuleId(id);
        ReviewRuleConfig active = queryService.loadRule(normalizedId);
        long activePolicyVersion = responseAssembler.positiveVersion(active.getPolicyVersion());
        List<ReviewRulePolicySnapshot> snapshots = policySnapshotStore.page(
            normalizedId,
            cursor,
            pageSize + 1
        );
        boolean hasMore = snapshots.size() > pageSize;
        List<ReviewRulePolicySnapshot> page = hasMore ? snapshots.subList(0, pageSize) : snapshots;
        List<ReviewRulePolicyVersionDto> items = page.stream()
            .map(snapshot -> responseAssembler.toVersionDto(snapshot, activePolicyVersion))
            .toList();
        String nextCursor = hasMore ? String.valueOf(page.getLast().getPolicyVersion()) : null;
        return new PageResponse<>(items, policySnapshotStore.count(normalizedId), nextCursor, hasMore);
    }

    private void validatePage(Long cursor, int pageSize) {
        if ((cursor != null && cursor < 1) || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid review rule history page");
        }
    }
}
