package com.vynk.coparent_backend.expense.dto;

import com.vynk.coparent_backend.expense.entity.ContestReason;
import com.vynk.coparent_backend.expense.entity.ExpenseCategory;
import com.vynk.coparent_backend.expense.entity.ExpenseStatus;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Une dépense. {@code owedCents} : ce que l'autre parent doit au payeur. {@code mine} : j'en suis l'auteur.
 * {@code canAnswer} : je peux l'accepter ou la contester ; {@code canEdit} : je peux la corriger ou la retirer.
 * {@code authorFirstName} : null si le compte a été supprimé. {@code receiptUpdatedAt} : null sans justificatif
 * photo (l'app s'en sert aussi pour rafraîchir l'image).
 */
public record ExpenseResponse(
        UUID id,
        ExpenseStatus status,
        String label,
        int amountCents,
        ExpenseCategory category,
        List<ChildRef> children,
        FamilyRole payerRole,
        LocalDate paidOn,
        int payerSharePercent,
        int owedCents,
        FamilyRole authorRole,
        String authorFirstName,
        boolean mine,
        boolean canAnswer,
        boolean canEdit,
        ContestReason contestReason,
        String contestNote,
        Instant receiptUpdatedAt,
        Instant createdAt,
        Instant answeredAt
) {

    public record ChildRef(UUID id, String firstName) {
    }
}
