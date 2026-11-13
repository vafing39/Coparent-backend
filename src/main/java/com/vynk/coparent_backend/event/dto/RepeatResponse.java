package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;

/** La règle d'un événement répété. {@code firstDate} : première séance ; {@code until} : dernier jour possible. */
public record RepeatResponse(
        RepeatFrequency frequency,
        FamilyRole onlyWith,
        boolean skipHolidays,
        RepeatEnd end,
        Integer count,
        LocalDate firstDate,
        LocalDate until
) {
}
