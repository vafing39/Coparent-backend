package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Répétition d'un événement. {@code onlyWith} : seulement quand les enfants sont chez ce parent (null : peu
 * importe) ; {@code count} : nombre de séances, seulement pour la fin COUNT.
 */
public record RepeatRequest(
        @NotNull RepeatFrequency frequency,
        FamilyRole onlyWith,
        boolean skipHolidays,
        @NotNull RepeatEnd end,
        @Min(1) @Max(99) Integer count
) {
}
