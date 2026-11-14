package com.vynk.coparent_backend.expense.service;

import com.vynk.coparent_backend.expense.entity.Expense;
import com.vynk.coparent_backend.expense.entity.ExpenseCategory;
import com.vynk.coparent_backend.expense.entity.Settlement;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceCalculatorTest {

    @Test
    void otherParentsShareIsRoundedToTheNearestCent() {
        assertThat(Expense.owedCents(8500, 50)).isEqualTo(4250);
        assertThat(Expense.owedCents(6421, 50)).isEqualTo(3211); // 32,105 → 32,11
        assertThat(Expense.owedCents(1001, 70)).isEqualTo(300);  // 3,003 → 3,00
        assertThat(Expense.owedCents(1005, 70)).isEqualTo(302);  // 3,015 → 3,02
        assertThat(Expense.owedCents(1000, 100)).isZero();
        assertThat(Expense.owedCents(1000, 0)).isEqualTo(1000);
    }

    @Test
    void balanceAddsValidatedExpensesAndSubtractsConfirmedSettlements() {
        Expense paidByA = expense(FamilyRole.PARENT_A, 8500, 50);  // B doit 42,50
        Expense paidByB = expense(FamilyRole.PARENT_B, 3780, 50);  // A doit 18,90
        assertThat(BalanceCalculator.owedByParentA(List.of(paidByA, paidByB), List.of())).isEqualTo(-2360);

        Settlement fromB = new Settlement(null, FamilyRole.PARENT_B, 2360);
        assertThat(BalanceCalculator.owedByParentA(List.of(paidByA, paidByB), List.of(fromB))).isZero();

        Settlement fromA = new Settlement(null, FamilyRole.PARENT_A, 1000);
        assertThat(BalanceCalculator.owedByParentA(List.of(paidByB), List.of(fromA))).isEqualTo(890);
    }

    private static Expense expense(FamilyRole payer, int amountCents, int payerShare) {
        Expense expense = new Expense(null, null, payer);
        expense.fill("Dépense", amountCents, ExpenseCategory.OTHER, payer, LocalDate.of(2026, 11, 1), payerShare,
                List.of());
        return expense;
    }
}
