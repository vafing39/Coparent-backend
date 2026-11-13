package com.vynk.coparent_backend.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement envoyé aux parents abonnés à {@code /topic/families/{id}/messages} : un nouveau message
 * ({@code MESSAGE}), ou des messages lus par {@code readerId} jusqu'à {@code upToSeq} ({@code READ}).
 */
public record MessageRealtimeEvent(Type type, MessageResponse message, UUID readerId, Long upToSeq,
                                   Instant readAt) {

    public enum Type { MESSAGE, READ }

    public static MessageRealtimeEvent message(MessageResponse message) {
        return new MessageRealtimeEvent(Type.MESSAGE, message, null, null, null);
    }

    public static MessageRealtimeEvent read(UUID readerId, long upToSeq, Instant readAt) {
        return new MessageRealtimeEvent(Type.READ, null, readerId, upToSeq, readAt);
    }
}
