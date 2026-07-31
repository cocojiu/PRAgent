package com.repoguard.agent.config;

import com.repoguard.agent.review.EnforcementMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.llm-verification")
public class LlmVerificationProperties {

    private boolean enabled = true;

    @Min(1)
    @Max(20)
    private int maxCandidates = 4;

    private String enforcementMode = EnforcementMode.COMMENT.name();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public String getEnforcementMode() {
        return enforcementMode;
    }

    public void setEnforcementMode(String enforcementMode) {
        this.enforcementMode = EnforcementMode.from(enforcementMode).name();
    }

    public EnforcementMode enforcementMode() {
        return EnforcementMode.from(enforcementMode);
    }
}
