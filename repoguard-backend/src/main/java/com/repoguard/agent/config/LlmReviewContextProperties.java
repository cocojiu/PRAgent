package com.repoguard.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.llm-context")
public class LlmReviewContextProperties {

    @Min(4_096)
    @Max(100_000)
    private int maxTotalChars = 24_000;

    @Min(512)
    @Max(20_000)
    private int maxSliceChars = 6_000;

    @Min(0)
    @Max(50)
    private int maxRelatedFiles = 8;

    @Min(1)
    @Max(100)
    private int maxRulePolicies = 20;

    @Min(80)
    @Max(2_000)
    private int maxRuleTextChars = 360;

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = maxTotalChars;
    }

    public int getMaxSliceChars() {
        return maxSliceChars;
    }

    public void setMaxSliceChars(int maxSliceChars) {
        this.maxSliceChars = maxSliceChars;
    }

    public int getMaxRelatedFiles() {
        return maxRelatedFiles;
    }

    public void setMaxRelatedFiles(int maxRelatedFiles) {
        this.maxRelatedFiles = maxRelatedFiles;
    }

    public int getMaxRulePolicies() {
        return maxRulePolicies;
    }

    public void setMaxRulePolicies(int maxRulePolicies) {
        this.maxRulePolicies = maxRulePolicies;
    }

    public int getMaxRuleTextChars() {
        return maxRuleTextChars;
    }

    public void setMaxRuleTextChars(int maxRuleTextChars) {
        this.maxRuleTextChars = maxRuleTextChars;
    }
}
