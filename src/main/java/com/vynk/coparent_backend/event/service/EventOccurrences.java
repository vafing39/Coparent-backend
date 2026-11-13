package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.event.entity.EventRepeat;
import com.vynk.coparent_backend.event.entity.RepeatMonthMode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Séances d'une série, calcul pur. Les dates partent de la première séance au pas de la fréquence (chaque mois :
 * même quantième, ramené à la fin d'un mois plus court ; personnalisé : tous les N jours, les jours choisis toutes
 * les N semaines, ou tous les N mois au même quantième ou au même rang du jour). {@code kept} écarte les dates
 * hors filtre (parent, vacances) : elles ne comptent pas comme séances. Les séances retirées ({@code skipped})
 * comptent dans un « après N séances » mais ne sont pas renvoyées.
 */
public final class EventOccurrences {

    /** Garde-fou : une série quotidienne sans fin couvre ainsi plus de treize ans. */
    static final int MAX_STEPS = 5000;

    private EventOccurrences() {
    }

    /** Séances de {@code from} à {@code to} inclus. {@code days} : jours d'une répétition personnalisée par semaine. */
    public static List<LocalDate> between(LocalDate first, EventRepeat repeat, Set<DayOfWeek> days,
                                          Set<LocalDate> skipped, Predicate<LocalDate> kept, LocalDate from,
                                          LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        int sessions = 0;
        Iterator<LocalDate> dates = candidates(first, repeat, days);
        for (int step = 0; step < MAX_STEPS; step++) {
            LocalDate date = dates.next();
            if (date.isAfter(to) || (repeat.getUntil() != null && date.isAfter(repeat.getUntil()))) {
                break;
            }
            if (!kept.test(date)) {
                continue;
            }
            sessions++;
            if (repeat.getCount() != null && sessions > repeat.getCount()) {
                break;
            }
            if (!date.isBefore(from) && !skipped.contains(date)) {
                result.add(date);
            }
        }
        return result;
    }

    /** Dates possibles de la série dans l'ordre, sans fin, à partir de la première séance. */
    static Iterator<LocalDate> candidates(LocalDate first, EventRepeat repeat, Set<DayOfWeek> days) {
        return switch (repeat.getFrequency()) {
            case DAILY -> stepping(step -> first.plusDays(step));
            case WEEKLY -> stepping(step -> first.plusWeeks(step));
            case BIWEEKLY -> stepping(step -> first.plusWeeks(2L * step));
            case MONTHLY -> stepping(step -> first.plusMonths(step));
            case CUSTOM -> {
                long n = repeat.getInterval();
                yield switch (repeat.getUnit()) {
                    case DAY -> stepping(step -> first.plusDays(n * step));
                    case WEEK -> weekDays(first, n, days);
                    case MONTH -> repeat.getMonthMode() == RepeatMonthMode.WEEKDAY_OF_MONTH
                            ? stepping(step -> sameWeekdayRank(first, first.plusMonths(n * step)))
                            : stepping(step -> first.plusMonths(n * step));
                };
            }
        };
    }

    private interface Step {
        LocalDate at(long step);
    }

    private static Iterator<LocalDate> stepping(Step step) {
        return new Iterator<>() {
            private long next;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public LocalDate next() {
                return step.at(next++);
            }
        };
    }

    /** Les jours choisis, une semaine sur {@code n} à partir de la semaine de la première séance. */
    private static Iterator<LocalDate> weekDays(LocalDate first, long n, Set<DayOfWeek> days) {
        List<DayOfWeek> sorted = new ArrayList<>(new TreeSet<>(days.isEmpty() ? Set.of(first.getDayOfWeek()) : days));
        LocalDate monday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Iterator<>() {
            private long week;
            private int index;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public LocalDate next() {
                while (true) {
                    LocalDate date = monday.plusWeeks(n * week).plusDays(sorted.get(index).getValue() - 1L);
                    if (++index == sorted.size()) {
                        index = 0;
                        week++;
                    }
                    if (!date.isBefore(first)) {
                        return date;
                    }
                }
            }
        };
    }

    /**
     * Dans le mois de {@code month}, le jour de même rang que {@code first} (le troisième jeudi) ; un cinquième
     * jour devient le dernier du mois.
     */
    static LocalDate sameWeekdayRank(LocalDate first, LocalDate month) {
        DayOfWeek day = first.getDayOfWeek();
        int rank = (first.getDayOfMonth() - 1) / 7 + 1;
        return rank >= 5
                ? month.with(TemporalAdjusters.lastInMonth(day))
                : month.with(TemporalAdjusters.dayOfWeekInMonth(rank, day));
    }
}
