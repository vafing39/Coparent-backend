package com.vynk.coparent_backend.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Un message, identique pour les deux parents (l'app compare {@code senderId} à son utilisateur).
 * {@code readAt} : quand le destinataire l'a lu, sinon null.
 */
public record MessageResponse(UUID id, UUID conversationId, long seq, UUID senderId, String senderFirstName, String body,
                              Instant createdAt, Instant readAt) {
}
