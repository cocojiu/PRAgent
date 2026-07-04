package com.repoguard.agent.worker;

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
        this(transactionOperationsOrNoop(transactionManager), maxAttempts);
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
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return transactionOperations.execute(() -> call(action));
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

    private static ReviewExecutionTransactionOperations transactionOperationsOrNoop(
        PlatformTransactionManager transactionManager
    ) {
        if (transactionManager == null) {
            return new NoopReviewExecutionTransactionOperations();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new SpringReviewExecutionTransactionOperations(template);
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
        <T> T execute(Supplier<T> action);

        boolean retryConcurrencyFailures();
    }

    private static class SpringReviewExecutionTransactionOperations implements ReviewExecutionTransactionOperations {

        private final TransactionTemplate transactionTemplate;

        SpringReviewExecutionTransactionOperations(TransactionTemplate transactionTemplate) {
            this.transactionTemplate = transactionTemplate;
        }

        @Override
        public <T> T execute(Supplier<T> action) {
            return transactionTemplate.execute(status -> action.get());
        }

        @Override
        public boolean retryConcurrencyFailures() {
            return true;
        }
    }

    private static class NoopReviewExecutionTransactionOperations implements ReviewExecutionTransactionOperations {

        @Override
        public <T> T execute(Supplier<T> action) {
            return action.get();
        }

        @Override
        public boolean retryConcurrencyFailures() {
            return false;
        }
    }
}
