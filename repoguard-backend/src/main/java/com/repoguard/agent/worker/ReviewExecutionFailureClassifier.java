package com.repoguard.agent.worker;

import com.repoguard.agent.external.ExternalCallException;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionFailureClassifier {

    String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }
}
