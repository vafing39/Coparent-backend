package com.vynk.coparent_backend.event.dto;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Un jour d'une répétition personnalisée par semaine, avec son horaire quand il diffère d'un jour à l'autre
 * (null : même horaire chaque jour, ou journée entière).
 */
public record RepeatDay(
        @NotNull DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime
) {
}
