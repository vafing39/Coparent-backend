package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.event.entity.RepeatMonthMode;
import com.vynk.coparent_backend.event.entity.RepeatUnit;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;
import java.util.List;

/**
 * La règle d'un événement répété. {@code firstDate} : première séance ; {@code until} : dernier jour possible ;
 * {@code days} : jours d'une répétition personnalisée par semaine, avec l'horaire effectif de chacun quand il
 * diffère (null partout : même horaire).
 */
public record RepeatResponse(
        RepeatFrequency frequency,
        Integer interval,
        RepeatUnit unit,
        RepeatMonthMode monthMode,
        List<RepeatDay> days,
        FamilyRole onlyWith,
        boolean skipHolidays,
        RepeatEnd end,
        Integer count,
        LocalDate firstDate,
        LocalDate until
) {
}
