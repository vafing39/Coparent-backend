package com.vynk.coparent_backend.message.realtime;

import com.vynk.coparent_backend.message.dto.MessageRealtimeEvent;

import java.util.UUID;

/** Publié par MessageService ; transmis aux abonnés une fois la transaction validée. */
public record MessagePublished(UUID familyId, MessageRealtimeEvent event) {

    public static String topic(UUID familyId) {
        return "/topic/families/" + familyId + "/messages";
    }
}
