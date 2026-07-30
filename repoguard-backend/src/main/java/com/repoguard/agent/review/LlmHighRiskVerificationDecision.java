package com.repoguard.agent.review;

record LlmHighRiskVerificationDecision(
    Verdict verdict,
    boolean evidenceSupported,
    boolean preconditionsSatisfied,
    boolean addedLineValid,
    boolean protectionPresent,
    String existingProtection,
    String confidence,
    String reason
) {

    enum Verdict {
        VERIFIED,
        REJECTED,
        UNCERTAIN
    }

    boolean verified() {
        return verdict == Verdict.VERIFIED
            && evidenceSupported
            && preconditionsSatisfied
            && addedLineValid
            && !protectionPresent;
    }
}
