package com.repoguard.agent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class ReviewRuleProvider {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper;

    public ReviewRuleProvider(ReviewRuleConfigMapper reviewRuleConfigMapper) {
        this.reviewRuleConfigMapper = reviewRuleConfigMapper;
    }

    public Map<String, ReviewRuleSettings> getRulesById() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(new LambdaQueryWrapper<>());
        if (CollectionUtils.isEmpty(rules)) {
            return Map.of();
        }
        Map<String, ReviewRuleSettings> rulesById = new HashMap<>();
        for (ReviewRuleConfig rule : rules) {
            if (rule == null || !StringUtils.hasText(rule.getId())) {
                continue;
            }
            rulesById.putIfAbsent(
                rule.getId(),
                new ReviewRuleSettings(rule.getId(), rule.getStatus(), rule.getFilePatterns())
            );
        }
        return Map.copyOf(rulesById);
    }
}
