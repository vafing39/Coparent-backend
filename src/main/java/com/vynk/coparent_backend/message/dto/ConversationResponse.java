package com.vynk.coparent_backend.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Une discussion : son sujet, son dernier message (elle en a toujours au moins un) et le nombre de messages
 * de l'autre parent que je n'ai pas encore lus.
 */
public record ConversationResponse(UUID id, String subject, String authorFirstName, Instant createdAt,
                                   MessageResponse lastMessage, long unreadCount) {
}
