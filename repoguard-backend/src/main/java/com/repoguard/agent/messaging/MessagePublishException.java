package com.repoguard.agent.messaging;

import com.repoguard.agent.review.task.ReviewTaskPublishException;

public class MessagePublishException extends ReviewTaskPublishException {

    private static final long serialVersionUID = 1L;

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
