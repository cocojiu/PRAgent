package com.repoguard.agent.dto;

public class ReviewRuleFeedbackStat {

    private Long totalHits;
    private Long validCount;
    private Long falsePositiveCount;
    private Long reviewedCount;

    public Long getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(Long totalHits) {
        this.totalHits = totalHits;
    }

    public Long getValidCount() {
        return validCount;
    }

    public void setValidCount(Long validCount) {
        this.validCount = validCount;
    }

    public Long getFalsePositiveCount() {
        return falsePositiveCount;
    }

    public void setFalsePositiveCount(Long falsePositiveCount) {
        this.falsePositiveCount = falsePositiveCount;
    }

    public Long getReviewedCount() {
        return reviewedCount;
    }

    public void setReviewedCount(Long reviewedCount) {
        this.reviewedCount = reviewedCount;
    }
}
