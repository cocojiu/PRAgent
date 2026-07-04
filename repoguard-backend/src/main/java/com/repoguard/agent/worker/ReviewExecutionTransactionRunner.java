package com.repoguard.agent.worker;

import java.util.concurrent.Callable;
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

    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;

    @Autowired
    ReviewExecutionTransactionRunner(PlatformTransactionManager transactionManager) {
        this(transactionManager, DEFAULT_MAX_ATTEMPTS);
    }

    ReviewExecutionTransactionRunner(PlatformTransactionManager transactionManager, int maxAttempts) {
        this.transactionTemplate = buildTransactionTemplate(transactionManager);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    <T> T execute(Callable<T> action) {
        if (transactionTemplate == null) {
            return call(action);
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return transactionTemplate.execute(status -> call(action));
            } catch (ConcurrencyFailureException ex) {
                if (attempt >= maxAttempts) {
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

    private TransactionTemplate buildTransactionTemplate(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return null;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
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
}
