package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.EventReminder;
import com.vynk.coparent_backend.event.entity.EventType;
import com.vynk.coparent_backend.event.entity.EventVisibility;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Un événement, en heure de Paris ({@code startTime}/{@code endTime} null pour une journée entière).
 * {@code authorFirstName} : null si le compte a été supprimé ; {@code mine} : j'en suis l'auteur.
 */
public record EventResponse(
        UUID id,
        String title,
        EventType type,
        List<EventChildResponse> children,
        boolean allDay,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String location,
        EventReminder reminder,
        EventVisibility visibility,
        String authorFirstName,
        boolean mine,
        Instant createdAt,
        Instant updatedAt
) {
}
