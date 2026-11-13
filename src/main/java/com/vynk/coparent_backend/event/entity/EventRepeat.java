package com.vynk.coparent_backend.event.entity;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Règle d'un événement répété. {@code until} : dernier jour possible (fin d'année scolaire, ou série arrêtée
 * quand on modifie « celui-ci et les suivants ») ; {@code count} : nombre de séances pour la fin COUNT.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventRepeat {

    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_frequency")
    private RepeatFrequency frequency;

    /** Seulement quand les enfants sont chez ce parent ; null : peu importe. */
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_only_with")
    private FamilyRole onlyWith;

    @Column(name = "repeat_skip_holidays")
    private Boolean skipHolidays;

    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_end")
    private RepeatEnd end;

    @Column(name = "repeat_count", columnDefinition = "smallint")
    private Integer count;

    @Column(name = "repeat_until")
    private LocalDate until;

    public EventRepeat(RepeatFrequency frequency, FamilyRole onlyWith, boolean skipHolidays, RepeatEnd end,
                       Integer count, LocalDate until) {
        this.frequency = frequency;
        this.onlyWith = onlyWith;
        this.skipHolidays = skipHolidays;
        this.end = end;
        this.count = count;
        this.until = until;
    }

    public boolean skipsHolidays() {
        return Boolean.TRUE.equals(skipHolidays);
    }

    /** La même règle, arrêtée au plus tard le {@code lastDay}. */
    public EventRepeat endingOn(LocalDate lastDay) {
        LocalDate bound = until == null || lastDay.isBefore(until) ? lastDay : until;
        return new EventRepeat(frequency, onlyWith, skipsHolidays(), end, count, bound);
    }
}
