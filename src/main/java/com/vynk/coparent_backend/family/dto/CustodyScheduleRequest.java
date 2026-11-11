package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.SummerSplit;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Étape 4 : rythme de garde et règles de vacances. {@code customSequence} : CUSTOM uniquement, une lettre
 * par nuit (A ou B). {@code handoverTime} : heure de Paris, par exemple "17:50".
 */
public record CustodyScheduleRequest(
        @NotNull CustodyPattern pattern,
        @Pattern(regexp = "[AB]{1,56}", message = "Une lettre par nuit, A ou B (56 au plus).") String customSequence,
        @NotNull LocalDate startDate,
        @NotNull LocalTime handoverTime,
        @NotNull FamilyRole startingParent,
        FamilyRole holidayFirstHalfEvenYears,
        SummerSplit summerSplit
) {
}
