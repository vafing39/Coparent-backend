package com.vynk.coparent_backend.expense.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Le solde courant : {@code debtorRole} doit {@code amountCents} à l'autre parent (null et 0 si le solde est réglé).
 * Calculé sur les dépenses validées moins les virements confirmés, détaillés dans {@code lines} (le plus récent
 * d'abord). {@code openSettlement} : le virement qui attend une confirmation, ou null.
 */
public record BalanceResponse(
        FamilyRole debtorRole,
        int amountCents,
        List<Line> lines,
        SettlementResponse openSettlement
) {

    /**
     * Une dépense validée ({@code label}, partagée à {@code payerSharePercent} %) ou un virement confirmé
     * ({@code label} et {@code payerSharePercent} null). {@code owedCents} : ce que cette ligne déplace dans le solde.
     */
    public record Line(
            Kind kind,
            UUID id,
            String label,
            int amountCents,
            FamilyRole payerRole,
            Integer payerSharePercent,
            int owedCents,
            LocalDate date
    ) {
    }

    public enum Kind {
        EXPENSE, SETTLEMENT
    }
}
