package com.repoguard.agent.worker;

import com.repoguard.agent.review.ReviewFindingResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingDeduplicator {

    private final ReviewFindingDeduplicationKeyResolver keyResolver;
    private final ReviewFindingMergeService mergeService;

    ReviewFindingDeduplicator(
        ReviewFindingDeduplicationKeyResolver keyResolver,
        ReviewFindingMergeService mergeService
    ) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.mergeService = Objects.requireNonNull(mergeService, "mergeService");
    }

    List<ReviewFindingResult> deduplicate(List<ReviewFindingResult> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ReviewFindingResult> byKey = new LinkedHashMap<>();
        for (ReviewFindingResult finding : findings) {
            String key = keyResolver.key(finding);
            ReviewFindingResult existing = byKey.get(key);
            byKey.put(key, existing == null ? finding : mergeService.merge(existing, finding));
        }
        return new ArrayList<>(byKey.values());
    }
}
