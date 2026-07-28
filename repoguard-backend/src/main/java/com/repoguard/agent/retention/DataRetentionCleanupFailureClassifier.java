package com.repoguard.agent.retention;

import com.repoguard.agent.common.BusinessException;
import org.springframework.dao.DataAccessException;

final class DataRetentionCleanupFailureClassifier {

    private DataRetentionCleanupFailureClassifier() {
    }

    static String classify(RuntimeException ex) {
        if (ex instanceof BusinessException) {
            return "bad_request";
        }
        if (ex instanceof DataAccessException) {
            return "database_error";
        }
        return "cleanup_failed";
    }
}
