package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;

/**
 * La nuit qui commence le {@code date} : chez qui sont les enfants. {@code holiday} : nom officiel des
 * vacances scolaires, null hors vacances.
 */
public record CustodyNightResponse(LocalDate date, FamilyRole parent, CustodyPeriod period, String holiday) {
}
