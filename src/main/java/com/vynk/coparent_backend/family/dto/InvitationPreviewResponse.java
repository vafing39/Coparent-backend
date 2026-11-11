package com.vynk.coparent_backend.family.dto;

import java.time.Instant;
import java.util.List;

/**
 * Ce que voit l'invité avant d'accepter (spec §3) : prénom de l'invitant, prénoms et âges des enfants,
 * rythme proposé. Ni le nom de la famille ni les dates de naissance : rien d'autre n'est partagé avant
 * l'acceptation.
 */
public record InvitationPreviewResponse(
        String inviterFirstName,
        List<ChildPreview> children,
        CustodyScheduleResponse custodySchedule,
        Instant expiresAt
) {

    public record ChildPreview(String firstName, int age) {
    }
}
