package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.event.entity.EventRepeat;
import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventOccurrencesTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 19);

    private static EventRepeat repeat(RepeatFrequency frequency, RepeatEnd end, Integer count, LocalDate until) {
        return new EventRepeat(frequency, null, false, end, count, until);
    }

    @Test
    void stepsFromTheFirstSession() {
        assertThat(EventOccurrences.between(THURSDAY, repeat(RepeatFrequency.BIWEEKLY, RepeatEnd.NEVER, null, null),
                Set.of(), d -> true, THURSDAY, THURSDAY.plusWeeks(5)))
                .containsExactly(THURSDAY, THURSDAY.plusWeeks(2), THURSDAY.plusWeeks(4));
        // Chaque mois : le 31 devient le 30 ou le 28, sans dériver ensuite
        LocalDate jan31 = LocalDate.of(2027, 1, 31);
        assertThat(EventOccurrences.between(jan31, repeat(RepeatFrequency.MONTHLY, RepeatEnd.NEVER, null, null),
                Set.of(), d -> true, jan31, LocalDate.of(2027, 4, 30)))
                .containsExactly(jan31, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
                        LocalDate.of(2027, 4, 30));
    }

    @Test
    void filteredDatesAreNotSessionsButSkippedOnesStillCount() {
        // Après 3 séances, sans les mercredis (filtre) ; la 2e séance est retirée mais compte
        LocalDate monday = LocalDate.of(2026, 11, 16);
        assertThat(EventOccurrences.between(monday, repeat(RepeatFrequency.DAILY, RepeatEnd.COUNT, 3, null),
                Set.of(monday.plusDays(1)), d -> d.getDayOfWeek() != DayOfWeek.WEDNESDAY,
                monday, monday.plusDays(30)))
                .containsExactly(monday, monday.plusDays(3));
    }

    @Test
    void untilAndPeriodBound() {
        assertThat(EventOccurrences.between(THURSDAY,
                repeat(RepeatFrequency.WEEKLY, RepeatEnd.SCHOOL_YEAR, null, THURSDAY.plusWeeks(2)), Set.of(),
                d -> true, THURSDAY.plusDays(1), THURSDAY.plusYears(1)))
                .containsExactly(THURSDAY.plusWeeks(1), THURSDAY.plusWeeks(2));
    }
}
