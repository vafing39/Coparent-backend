package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.RelativeRelation;

import java.time.Instant;
import java.util.List;

/**
 * Ce que voit le proche avant de rejoindre : son prénom et son lien de parenté tels que saisis, les prénoms des
 * parents qui l'invitent et ceux des enfants. Rien d'autre avant l'acceptation.
 */
public record RelativeInvitationPreviewResponse(
        String firstName,
        RelativeRelation relation,
        List<String> parentFirstNames,
        List<String> childFirstNames,
        Instant expiresAt
) {
}
