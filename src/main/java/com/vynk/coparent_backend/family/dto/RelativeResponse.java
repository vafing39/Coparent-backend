package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.RelativeRelation;
import com.vynk.coparent_backend.family.entity.RelativeStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Un proche vu par un parent. {@code status} : PROPOSED (attend la validation de l'autre parent), INVITED (lien
 * envoyé, {@code expiresAt}, {@code expired}) ou ACTIVE. {@code canApprove} : c'est à moi de valider ou refuser
 * (l'autre parent que le proposant, deux parents). {@code email} : null une fois actif.
 */
public record RelativeResponse(
        UUID id,
        String firstName,
        String email,
        RelativeRelation relation,
        RelativeStatus status,
        FamilyRole proposedByRole,
        String proposedByFirstName,
        Instant createdAt,
        Instant approvedAt,
        Instant joinedAt,
        Instant expiresAt,
        boolean expired,
        boolean canApprove
) {
}
