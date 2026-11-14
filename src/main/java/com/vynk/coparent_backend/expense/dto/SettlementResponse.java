package com.vynk.coparent_backend.expense.dto;

import com.vynk.coparent_backend.expense.entity.SettlementStatus;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.util.UUID;

/** Un virement. {@code mine} : je l'ai déclaré (je peux l'annuler) ; sinon je confirme sa réception. */
public record SettlementResponse(
        UUID id,
        FamilyRole payerRole,
        int amountCents,
        SettlementStatus status,
        boolean mine,
        Instant declaredAt,
        Instant answeredAt
) {
}
