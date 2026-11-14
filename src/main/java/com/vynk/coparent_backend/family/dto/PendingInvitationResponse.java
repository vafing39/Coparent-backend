package com.vynk.coparent_backend.family.dto;

import java.time.Instant;

/**
 * Dernier lien d'invitation pas encore accepté ni remplacé (mode solo). Le lien lui-même n'est pas renvoyé :
 * seul son hash est stocké ; pour le renvoyer, il faut en créer un nouveau.
 */
public record PendingInvitationResponse(String inviteeFirstName, Instant sentAt, Instant expiresAt,
                                        boolean expired) {
}
