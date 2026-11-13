package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.EventChangeAction;
import com.vynk.coparent_backend.event.entity.EventField;
import com.vynk.coparent_backend.event.entity.EventScope;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ligne de l'historique. {@code scope} et {@code occurrenceDate} : séances concernées d'une série ;
 * {@code fields} : ce qu'une modification a changé ; {@code authorFirstName} null si le compte a été supprimé.
 */
public record EventChangeResponse(
        UUID id,
        EventChangeAction action,
        EventScope scope,
        LocalDate occurrenceDate,
        List<EventField> fields,
        String authorFirstName,
        boolean mine,
        Instant createdAt
) {
}
