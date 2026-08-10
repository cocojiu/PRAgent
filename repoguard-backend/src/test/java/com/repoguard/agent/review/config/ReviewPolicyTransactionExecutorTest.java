package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ReviewPolicyTransactionExecutorTest {

    @Test
    void executesPolicyWritesInARequiresNewTransaction() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        ReviewPolicyTransactionExecutor executor = new ReviewPolicyTransactionExecutor(transactionManager);

        String result = executor.write(() -> "saved");

        assertThat(result).isEqualTo("saved");
        assertThat(transactionManager.propagationBehavior)
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionManager.commits).hasValue(1);
        assertThat(transactionManager.rollbacks).hasValue(0);
    }

    @Test
    void rollsBackTheShortWriteTransactionWhenTheCommandFails() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        ReviewPolicyTransactionExecutor executor = new ReviewPolicyTransactionExecutor(transactionManager);

        assertThatThrownBy(() -> executor.write(() -> {
            throw new IllegalStateException("write failed");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("write failed");

        assertThat(transactionManager.commits).hasValue(0);
        assertThat(transactionManager.rollbacks).hasValue(1);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private int propagationBehavior;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            propagationBehavior = definition.getPropagationBehavior();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }
}
