package com.repoguard.agent.review.task;

public class ReviewTaskPublishException extends RuntimeException {

    public ReviewTaskPublishException(String message) {
        super(message);
    }

    public ReviewTaskPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
