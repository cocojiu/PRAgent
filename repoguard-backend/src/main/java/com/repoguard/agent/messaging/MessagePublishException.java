package com.repoguard.agent.messaging;

import com.repoguard.agent.review.task.ReviewTaskPublishException;

public class MessagePublishException extends ReviewTaskPublishException {

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
