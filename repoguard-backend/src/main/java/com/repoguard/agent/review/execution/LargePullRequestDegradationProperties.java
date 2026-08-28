package com.repoguard.agent.review.execution;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.execution.large-pr")
public class LargePullRequestDegradationProperties {

    private boolean enabled = true;

    @Min(1)
    private int maxFilesForLlm = 300;

    @Min(1)
    private int maxChangesForLlm = 15_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxFilesForLlm() { return maxFilesForLlm; }
    public void setMaxFilesForLlm(int maxFilesForLlm) { this.maxFilesForLlm = maxFilesForLlm; }
    public int getMaxChangesForLlm() { return maxChangesForLlm; }
    public void setMaxChangesForLlm(int maxChangesForLlm) { this.maxChangesForLlm = maxChangesForLlm; }
}
