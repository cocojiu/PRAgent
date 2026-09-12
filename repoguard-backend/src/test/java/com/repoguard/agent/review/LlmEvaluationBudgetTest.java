package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LlmEvaluationBudgetTest {

    @Test
    void refusesAProviderCallBeforeItsConservativeReservationExceedsTokenLimit() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(1_100, BigDecimal.TEN);

        assertThatThrownBy(() -> budget.reserve(
            "system",
            "user",
            128,
            BigDecimal.ONE,
            BigDecimal.ONE
        )).isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);

        assertThat(budget.accountedTokens()).isZero();
        assertThat(budget.accountedCost()).isZero();
        assertThatThrownBy(budget::requireAvailable)
            .isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
        assertThatThrownBy(() -> budget.reserve("", "", 0, BigDecimal.ZERO, BigDecimal.ZERO))
            .isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
    }

    @Test
    void reconcilesSuccessfulUsageAndReleasesUnusedReservation() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(10_000, BigDecimal.TEN);
        LlmEvaluationBudget.Reservation reservation = budget.reserve(
            "system",
            "user",
            2_000,
            new BigDecimal("2.00"),
            new BigDecimal("4.00")
        );

        reservation.complete(
            new LlmCallResult("{}", 100, 25, 125),
            new BigDecimal("2.00"),
            new BigDecimal("4.00")
        );

        assertThat(budget.accountedTokens()).isEqualTo(125);
        assertThat(budget.accountedCost()).isEqualByComparingTo("0.0003");
    }

    @Test
    void retainsReservationWhenProviderDoesNotReturnUsage() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(10_000, BigDecimal.TEN);
        LlmEvaluationBudget.Reservation reservation = budget.reserve(
            "system",
            "user",
            2_000,
            BigDecimal.ONE,
            BigDecimal.ONE
        );
        long reservedTokens = budget.accountedTokens();
        BigDecimal reservedCost = budget.accountedCost();

        reservation.complete(
            new LlmCallResult("{}", null, null, null),
            BigDecimal.ONE,
            BigDecimal.ONE
        );

        assertThat(budget.accountedTokens()).isEqualTo(reservedTokens);
        assertThat(budget.accountedCost()).isEqualByComparingTo(reservedCost);
    }

    @Test
    void failedCallKeepsReservationAndBlocksTheNextConcurrentCall() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(2_500, BigDecimal.TEN);

        LlmEvaluationBudget.Reservation reservation = budget.reserve(
            "system",
            "user",
            1_000,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        reservation.close(); // Simulate an outbound failure with unknown provider usage.

        assertThatThrownBy(() -> budget.reserve(
            "system",
            "user",
            1_000,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )).isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
    }

    @Test
    void enforcesCostLimitIndependentlyOfTokenLimit() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(1_000_000, new BigDecimal("0.001"));

        assertThatThrownBy(() -> budget.reserve(
            "system",
            "user",
            1_000,
            BigDecimal.ZERO,
            new BigDecimal("2.00")
        )).isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
    }

    @Test
    void incompleteUsageKeepsConservativeReservation() {
        for (LlmCallResult usage : new LlmCallResult[] {
            new LlmCallResult("{}", null, null, 125),
            new LlmCallResult("{}", 100, null, 125),
            new LlmCallResult("{}", null, 25, 125),
            new LlmCallResult("{}", 100, 25, 150),
            new LlmCallResult("{}", -1, 25, 25)
        }) {
            LlmEvaluationBudget budget = new LlmEvaluationBudget(10000, BigDecimal.TEN);
            var reservation = budget.reserve("system", "user", 2000, new BigDecimal("2"), new BigDecimal("8"));
            long tokens = budget.accountedTokens();
            BigDecimal cost = budget.accountedCost();
            reservation.complete(usage, new BigDecimal("2"), new BigDecimal("8"));
            assertThat(budget.accountedTokens()).isEqualTo(tokens);
            assertThat(budget.accountedCost()).isEqualByComparingTo(cost);
        }
    }

    @Test
    void reportedExcessWithoutSplitCanExhaustCostBudget() {
        LlmEvaluationBudget budget = new LlmEvaluationBudget(100000, new BigDecimal("0.03"));
        var reservation = budget.reserve("", "", 1000, new BigDecimal("2"), new BigDecimal("8"));
        assertThatThrownBy(() -> reservation.complete(
            new LlmCallResult("{}", null, null, 10000), new BigDecimal("2"), new BigDecimal("8")
        )).isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
        assertThat(budget.accountedCost()).isEqualByComparingTo("0.08");
        assertThatThrownBy(budget::requireAvailable).isInstanceOf(LlmEvaluationBudget.BudgetExceededException.class);
    }
}
