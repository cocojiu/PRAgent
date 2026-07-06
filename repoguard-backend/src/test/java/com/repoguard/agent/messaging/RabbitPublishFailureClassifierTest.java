package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RabbitPublishFailureClassifierTest {

    private final RabbitPublishFailureClassifier classifier = new RabbitPublishFailureClassifier();

    @Test
    void classifiesKnownRabbitPublishFailures() {
        assertThat(classifier.classify(new MessagePublishException("RabbitMQ message was returned as unroutable")))
            .isEqualTo("unroutable");
        assertThat(classifier.classify(new MessagePublishException("RabbitMQ publisher confirm was nacked: broker refused")))
            .isEqualTo("nacked");
        assertThat(classifier.classify(new MessagePublishException("RabbitMQ publisher confirm timed out")))
            .isEqualTo("confirm_timeout");
        assertThat(classifier.classify(new MessagePublishException("RabbitMQ publish retry sleep was interrupted")))
            .isEqualTo("interrupted");
    }

    @Test
    void defaultsUnknownOrBlankFailuresToPublishFailed() {
        assertThat(classifier.classify(null)).isEqualTo("publish_failed");
        assertThat(classifier.classify(new MessagePublishException(" "))).isEqualTo("publish_failed");
        assertThat(classifier.classify(new MessagePublishException("RabbitMQ message publish attempt failed")))
            .isEqualTo("publish_failed");
    }
}
