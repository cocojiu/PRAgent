package com.repoguard.agent.review;

record EvidenceValidation(
    boolean anchorValid,
    boolean evidenceVerified,
    int confidencePenalty,
    String reason
) {

    static EvidenceValidation forRuleMatch(RuleMatch match) {
        boolean anchorValid = match != null && match.lineNumber() != null && match.lineNumber() > 0;
        boolean verified = match != null && match.evidenceVerified();
        int penalty = 0;
        String reason = "rule_match_verified";
        if (!anchorValid) {
            penalty += 40;
            reason = "missing_changed_line_anchor";
        }
        if (!verified) {
            penalty += 20;
            reason = anchorValid ? "evidence_not_verified" : reason + ",evidence_not_verified";
        }
        return new EvidenceValidation(anchorValid, verified, penalty, reason);
    }
}
