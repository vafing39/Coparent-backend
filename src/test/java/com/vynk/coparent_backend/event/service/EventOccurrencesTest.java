package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.event.entity.EventRepeat;
import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.event.entity.RepeatMonthMode;
import com.vynk.coparent_backend.event.entity.RepeatUnit;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventOccurrencesTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 19);
    private static final Set<DayOfWeek> NO_DAYS = Set.of();

    private static EventRepeat custom(int interval, RepeatUnit unit, RepeatMonthMode monthMode, Integer count) {
        return new EventRepeat(RepeatFrequency.CUSTOM, interval, unit, monthMode, null, false,
                count == null ? RepeatEnd.NEVER : RepeatEnd.COUNT, count, null);
    }

    private static EventRepeat repeat(RepeatFrequency frequency, RepeatEnd end, Integer count, LocalDate until) {
        return new EventRepeat(frequency, null, null, null, null, false, end, count, until);
    }

    @Test
    void stepsFromTheFirstSession() {
        assertThat(EventOccurrences.between(THURSDAY, repeat(RepeatFrequency.BIWEEKLY, RepeatEnd.NEVER, null, null), NO_DAYS,
                Set.of(), d -> true, THURSDAY, THURSDAY.plusWeeks(5)))
                .containsExactly(THURSDAY, THURSDAY.plusWeeks(2), THURSDAY.plusWeeks(4));
        // Chaque mois : le 31 devient le 30 ou le 28, sans dériver ensuite
        LocalDate jan31 = LocalDate.of(2027, 1, 31);
        assertThat(EventOccurrences.between(jan31, repeat(RepeatFrequency.MONTHLY, RepeatEnd.NEVER, null, null), NO_DAYS,
                Set.of(), d -> true, jan31, LocalDate.of(2027, 4, 30)))
                .containsExactly(jan31, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
                        LocalDate.of(2027, 4, 30));
    }

    @Test
    void filteredDatesAreNotSessionsButSkippedOnesStillCount() {
        // Après 3 séances, sans les mercredis (filtre) ; la 2e séance est retirée mais compte
        LocalDate monday = LocalDate.of(2026, 11, 16);
        assertThat(EventOccurrences.between(monday, repeat(RepeatFrequency.DAILY, RepeatEnd.COUNT, 3, null), NO_DAYS,
                Set.of(monday.plusDays(1)), d -> d.getDayOfWeek() != DayOfWeek.WEDNESDAY,
                monday, monday.plusDays(30)))
                .containsExactly(monday, monday.plusDays(3));
    }

    @Test
    void untilAndPeriodBound() {
        assertThat(EventOccurrences.between(THURSDAY,
                repeat(RepeatFrequency.WEEKLY, RepeatEnd.SCHOOL_YEAR, null, THURSDAY.plusWeeks(2)), NO_DAYS, Set.of(),
                d -> true, THURSDAY.plusDays(1), THURSDAY.plusYears(1)))
                .containsExactly(THURSDAY.plusWeeks(1), THURSDAY.plusWeeks(2));
    }

    @Test
    void customEveryNDays() {
        assertThat(EventOccurrences.between(THURSDAY, custom(3, RepeatUnit.DAY, null, null), NO_DAYS, Set.of(),
                d -> true, THURSDAY, THURSDAY.plusDays(9)))
                .containsExactly(THURSDAY, THURSDAY.plusDays(3), THURSDAY.plusDays(6), THURSDAY.plusDays(9));
    }

    @Test
    void customChosenDaysEveryOtherWeek() {
        // Toutes les 2 semaines, lundi et mercredi, à partir du mercredi 18 novembre 2026
        LocalDate wednesday = LocalDate.of(2026, 11, 18);
        assertThat(EventOccurrences.between(wednesday, custom(2, RepeatUnit.WEEK, null, null),
                Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), Set.of(), d -> true, wednesday,
                LocalDate.of(2026, 12, 20)))
                .containsExactly(wednesday, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 2),
                        LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 16));
        // Le compte vaut pour chaque jour
        assertThat(EventOccurrences.between(wednesday, custom(1, RepeatUnit.WEEK, null, 3),
                Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), Set.of(), d -> true, wednesday,
                wednesday.plusYears(1)))
                .containsExactly(wednesday, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25));
    }

    @Test
    void customMonthlySameWeekdayRank() {
        // Jeudi 19 novembre 2026 : troisième jeudi ; tous les 2 mois
        assertThat(EventOccurrences.between(THURSDAY, custom(2, RepeatUnit.MONTH, RepeatMonthMode.WEEKDAY_OF_MONTH,
                null), NO_DAYS, Set.of(), d -> true, THURSDAY, LocalDate.of(2027, 3, 31)))
                .containsExactly(THURSDAY, LocalDate.of(2027, 1, 21), LocalDate.of(2027, 3, 18));
        // Vendredi 30 octobre 2026 : cinquième vendredi, donc le dernier du mois
        LocalDate lastFriday = LocalDate.of(2026, 10, 30);
        assertThat(EventOccurrences.between(lastFriday, custom(1, RepeatUnit.MONTH,
                RepeatMonthMode.WEEKDAY_OF_MONTH, null), NO_DAYS, Set.of(), d -> true, lastFriday,
                LocalDate.of(2027, 1, 31)))
                .containsExactly(lastFriday, LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 25),
                        LocalDate.of(2027, 1, 29));
        // Le même quantième
        assertThat(EventOccurrences.between(THURSDAY, custom(3, RepeatUnit.MONTH, RepeatMonthMode.DAY_OF_MONTH,
                2), NO_DAYS, Set.of(), d -> true, THURSDAY, THURSDAY.plusYears(1)))
                .containsExactly(THURSDAY, LocalDate.of(2027, 2, 19));
    }
}
