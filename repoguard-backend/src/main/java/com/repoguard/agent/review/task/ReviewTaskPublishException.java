package com.repoguard.agent.review.task;

public class ReviewTaskPublishException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReviewTaskPublishException(String message) {
        super(message);
    }

    public ReviewTaskPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
