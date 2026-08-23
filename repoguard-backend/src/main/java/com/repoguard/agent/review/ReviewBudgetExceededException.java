package com.repoguard.agent.review;

public class ReviewBudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CATEGORY = "execution_budget_exhausted";

    private final String stage;

    public ReviewBudgetExceededException(String stage) {
        super(CATEGORY + ":" + normalize(stage));
        this.stage = normalize(stage);
    }

    public String stage() {
        return stage;
    }

    private static String normalize(String stage) {
        return stage == null || stage.isBlank() ? "unknown" : stage.trim();
    }
}
