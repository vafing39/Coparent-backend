package com.vynk.coparent_backend.expense.dto;

import com.vynk.coparent_backend.expense.entity.ExpenseCategory;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Nouvelle dépense ou correction. Montant en centimes ; {@code payerSharePercent} : part du payeur (50, 60, 70 ou
 * 100, ou l'inverse : 40, 30, 0). Le montant dû par l'autre parent est calculé par le serveur.
 */
public record ExpenseRequest(
        @NotBlank @Size(max = 120) String label,
        @NotNull Integer amountCents,
        @NotNull ExpenseCategory category,
        @NotNull FamilyRole payerRole,
        @NotNull LocalDate paidOn,
        @NotNull Integer payerSharePercent,
        @NotEmpty @Size(max = 20) List<UUID> childIds
) {
}
