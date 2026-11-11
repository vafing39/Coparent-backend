package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import com.vynk.coparent_backend.family.entity.SummerSplit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record CustodyScheduleResponse(
        UUID id,
        CustodyPattern pattern,
        String customSequence,
        LocalDate startDate,
        LocalTime handoverTime,
        FamilyRole startingParent,
        FamilyRole holidayFirstHalfEvenYears,
        SummerSplit summerSplit,
        ScheduleStatus status
) {
}
