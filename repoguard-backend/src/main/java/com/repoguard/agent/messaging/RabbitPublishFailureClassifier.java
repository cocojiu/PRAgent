package com.repoguard.agent.messaging;

import java.util.Locale;

public class RabbitPublishFailureClassifier {

    public String classify(MessagePublishException ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "publish_failed";
        }
        String message = ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("unroutable")) {
            return "unroutable";
        }
        if (message.contains("nacked")) {
            return "nacked";
        }
        if (message.contains("timed out")) {
            return "confirm_timeout";
        }
        if (message.contains("interrupted")) {
            return "interrupted";
        }
        return "publish_failed";
    }
}
