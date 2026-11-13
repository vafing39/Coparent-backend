package com.vynk.coparent_backend.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement envoyé aux parents abonnés à {@code /topic/families/{id}/messages} : un nouveau message
 * ({@code MESSAGE}, y compris le premier d'une nouvelle discussion), ou des messages de la discussion
 * {@code conversationId} lus par {@code readerId} jusqu'à {@code upToSeq} ({@code READ}).
 */
public record MessageRealtimeEvent(Type type, MessageResponse message, UUID conversationId, UUID readerId,
                                   Long upToSeq, Instant readAt) {

    public enum Type { MESSAGE, READ }

    public static MessageRealtimeEvent message(MessageResponse message) {
        return new MessageRealtimeEvent(Type.MESSAGE, message, message.conversationId(), null, null, null);
    }

    public static MessageRealtimeEvent read(UUID conversationId, UUID readerId, long upToSeq, Instant readAt) {
        return new MessageRealtimeEvent(Type.READ, null, conversationId, readerId, upToSeq, readAt);
    }
}
