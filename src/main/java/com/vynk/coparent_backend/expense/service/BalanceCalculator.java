package com.vynk.coparent_backend.expense.service;

import com.vynk.coparent_backend.expense.entity.Expense;
import com.vynk.coparent_backend.expense.entity.Settlement;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.util.Collection;

/**
 * Le solde courant, en centimes, vu du parent A : positif quand A doit à B, négatif quand B doit à A. Une dépense
 * validée payée par B ajoute la part de A ; payée par A, elle retire la part de B. Un virement confirmé de A réduit
 * ce que A doit, un virement de B l'augmente.
 */
final class BalanceCalculator {

    private BalanceCalculator() {
    }

    static long owedByParentA(Collection<Expense> validatedExpenses, Collection<Settlement> confirmedSettlements) {
        long owed = 0;
        for (Expense expense : validatedExpenses) {
            owed += expense.getPayerRole() == FamilyRole.PARENT_B ? expense.owedCents() : -expense.owedCents();
        }
        for (Settlement settlement : confirmedSettlements) {
            owed += settlement.getPayerRole() == FamilyRole.PARENT_A
                    ? -settlement.getAmountCents()
                    : settlement.getAmountCents();
        }
        return owed;
    }
}
