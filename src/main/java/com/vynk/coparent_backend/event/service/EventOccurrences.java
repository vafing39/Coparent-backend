package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.event.entity.EventRepeat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Séances d'une série, calcul pur. Les dates partent de la première séance au pas de la fréquence (chaque mois :
 * même quantième, ramené à la fin d'un mois plus court). {@code kept} écarte les dates hors filtre (parent,
 * vacances) : elles ne comptent pas comme séances. Les séances retirées ({@code skipped}) comptent dans un
 * « après N séances » mais ne sont pas renvoyées.
 */
public final class EventOccurrences {

    /** Garde-fou : une série quotidienne sans fin couvre ainsi plus de treize ans. */
    static final int MAX_STEPS = 5000;

    private EventOccurrences() {
    }

    /** Séances de {@code from} à {@code to} inclus. */
    public static List<LocalDate> between(LocalDate first, EventRepeat repeat, Set<LocalDate> skipped,
                                          Predicate<LocalDate> kept, LocalDate from, LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        int sessions = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            LocalDate date = dateAt(first, repeat, step);
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

    static LocalDate dateAt(LocalDate first, EventRepeat repeat, int step) {
        return switch (repeat.getFrequency()) {
            case DAILY -> first.plusDays(step);
            case WEEKLY -> first.plusWeeks(step);
            case BIWEEKLY -> first.plusWeeks(2L * step);
            case MONTHLY -> first.plusMonths(step);
        };
    }
}
