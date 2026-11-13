package com.vynk.coparent_backend.exchange.dto;

import com.vynk.coparent_backend.exchange.entity.ExchangeStatus;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une demande d'échange. {@code mine} : c'est ma demande (je peux l'annuler) ; sinon j'y réponds.
 * {@code requestedByFirstName} : null si le compte a été supprimé ; {@code giveFrom}/{@code giveTo} : null sans
 * contrepartie.
 */
public record ExchangeResponse(
        UUID id,
        ExchangeStatus status,
        FamilyRole requesterRole,
        String requestedByFirstName,
        boolean mine,
        LocalDate takeFrom,
        LocalDate takeTo,
        LocalDate giveFrom,
        LocalDate giveTo,
        String message,
        Instant createdAt,
        Instant answeredAt
) {
}
