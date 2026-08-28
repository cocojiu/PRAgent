package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.review.ReviewDeadline;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ReviewExecutionTransactionRunnerTest {

    @Test
    void rejectsMissingTransactionManager() {
        assertThatThrownBy(() -> new ReviewExecutionTransactionRunner(null, 3))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("transactionManager");
    }

    @Test
    void retriesConcurrencyFailureWithReadCommittedTransaction() {
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReviewExecutionTransactionRunner runner = new ReviewExecutionTransactionRunner(transactionManager, 3);
        AtomicInteger attempts = new AtomicInteger();

        String result = runner.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("deadlock");
            }
            return "completed";
        });

        assertThat(result).isEqualTo("completed");
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager).commit(transactionStatus);
        org.mockito.ArgumentCaptor<TransactionDefinition> definitionCaptor =
            org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitionCaptor.capture());
        assertThat(definitionCaptor.getAllValues())
            .allSatisfy(definition -> assertThat(definition.getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED));
    }

    @Test
    void rethrowsConcurrencyFailureAfterMaxAttempts() {
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReviewExecutionTransactionRunner runner = new ReviewExecutionTransactionRunner(transactionManager, 2);

        assertThatThrownBy(() -> runner.execute(() -> {
            throw new CannotAcquireLockException("deadlock");
        })).isInstanceOf(CannotAcquireLockException.class);

        verify(transactionManager, times(2)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    @Test
    void appliesCeilingOfRemainingDeadlineAsTransactionTimeout() {
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReviewExecutionTransactionRunner runner = new ReviewExecutionTransactionRunner(transactionManager, 1);
        ReviewDeadline deadline = ReviewDeadline.startingAt(0L, Duration.ofMillis(2_500), () -> 0L);

        assertThat(runner.execute(deadline, "persist", () -> "done")).isEqualTo("done");

        org.mockito.ArgumentCaptor<TransactionDefinition> definitionCaptor =
            org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getTimeout()).isEqualTo(3);
    }

    @Test
    void leavesTransactionTimeoutAtDefaultForUnlimitedDeadline() {
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReviewExecutionTransactionRunner runner = new ReviewExecutionTransactionRunner(transactionManager, 1);

        assertThat(runner.execute(ReviewDeadline.unlimited(), "persist", () -> "done")).isEqualTo("done");

        org.mockito.ArgumentCaptor<TransactionDefinition> definitionCaptor =
            org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }
}
