package com.vynk.coparent_backend.family.dto;

import java.time.Instant;

/** Lien à partager. Le jeton n'est renvoyé qu'ici : seul son hash est stocké. */
public record InvitationResponse(String link, Instant expiresAt) {
}
