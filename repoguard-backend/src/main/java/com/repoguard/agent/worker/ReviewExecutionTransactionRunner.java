package com.repoguard.agent.worker;

import com.repoguard.agent.review.ReviewDeadline;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ReviewExecutionTransactionRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewExecutionTransactionRunner.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ReviewExecutionTransactionOperations transactionOperations;
    private final int maxAttempts;

    @Autowired
    ReviewExecutionTransactionRunner(PlatformTransactionManager transactionManager) {
        this(transactionManager, DEFAULT_MAX_ATTEMPTS);
    }

    ReviewExecutionTransactionRunner(PlatformTransactionManager transactionManager, int maxAttempts) {
        this(transactionOperations(transactionManager), maxAttempts);
    }

    private ReviewExecutionTransactionRunner(
        ReviewExecutionTransactionOperations transactionOperations,
        int maxAttempts
    ) {
        this.transactionOperations = transactionOperations;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    <T> T execute(Callable<T> action) {
        return execute(null, "database", action);
    }

    <T> T execute(ReviewDeadline deadline, String stage, Callable<T> action) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int timeoutSeconds = timeoutSeconds(deadline, stage);
                return transactionOperations.execute(() -> call(action), timeoutSeconds);
            } catch (ConcurrencyFailureException ex) {
                if (!transactionOperations.retryConcurrencyFailures() || attempt >= maxAttempts) {
                    throw ex;
                }
                LOGGER.warn(
                    "Review task transaction concurrency conflict operation=review_transaction_retry attempt={} maxAttempts={} exceptionType={}",
                    attempt,
                    maxAttempts,
                    ex.getClass().getName()
                );
            }
        }
        throw new IllegalStateException("Review transaction retry loop exited unexpectedly");
    }

    private int timeoutSeconds(ReviewDeadline deadline, String stage) {
        if (deadline == null) {
            return TransactionDefinition.TIMEOUT_DEFAULT;
        }
        deadline.requireRemaining(stage);
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos == Long.MAX_VALUE) {
            return TransactionDefinition.TIMEOUT_DEFAULT;
        }
        long seconds = Math.max(1L, ((remainingNanos - 1L) / 1_000_000_000L) + 1L);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    private static ReviewExecutionTransactionOperations transactionOperations(
        PlatformTransactionManager transactionManager
    ) {
        return new SpringReviewExecutionTransactionOperations(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
    }

    private <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private interface ReviewExecutionTransactionOperations {
        <T> T execute(Supplier<T> action, int timeoutSeconds);

        boolean retryConcurrencyFailures();
    }

    private static class SpringReviewExecutionTransactionOperations implements ReviewExecutionTransactionOperations {

        private final PlatformTransactionManager transactionManager;

        SpringReviewExecutionTransactionOperations(PlatformTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
        }

        @Override
        public <T> T execute(Supplier<T> action, int timeoutSeconds) {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            if (timeoutSeconds != TransactionDefinition.TIMEOUT_DEFAULT) {
                transactionTemplate.setTimeout(timeoutSeconds);
            }
            return transactionTemplate.execute(status -> action.get());
        }

        @Override
        public boolean retryConcurrencyFailures() {
            return true;
        }
    }

}
