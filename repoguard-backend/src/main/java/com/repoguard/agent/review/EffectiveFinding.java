package com.repoguard.agent.review;

record EffectiveFinding(
    RuleMatch match,
    String severity,
    int confidenceScore,
    String confidence,
    EnforcementMode enforcementMode,
    boolean blocking,
    String policyReason
) {
}
