package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.EventReminder;
import com.vynk.coparent_backend.event.entity.EventType;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Création ou modification d'un événement. {@code date}, {@code startTime} et {@code endTime} sont en heure de
 * Paris ; les heures sont absentes pour une journée entière.
 */
public record EventRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull EventType type,
        @NotEmpty Set<UUID> childIds,
        @NotNull LocalDate date,
        boolean allDay,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 200) String location,
        @NotNull EventReminder reminder,
        @NotNull EventVisibility visibility
) {
}
