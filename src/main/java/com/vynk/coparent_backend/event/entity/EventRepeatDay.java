package com.vynk.coparent_backend.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Un jour d'une répétition personnalisée par semaine. Horaires null : ceux de l'événement (même horaire chaque
 * jour, ou jour de l'événement lui-même).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventRepeatDay {

    @Column(name = "day_of_week", nullable = false, columnDefinition = "tinyint")
    private int dayOfWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    public EventRepeatDay(DayOfWeek day, LocalTime startTime, LocalTime endTime) {
        this.dayOfWeek = day.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public DayOfWeek day() {
        return DayOfWeek.of(dayOfWeek);
    }

    public boolean hasOwnTime() {
        return startTime != null;
    }
}
