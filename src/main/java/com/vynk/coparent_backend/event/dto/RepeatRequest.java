package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.event.entity.RepeatMonthMode;
import com.vynk.coparent_backend.event.entity.RepeatUnit;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Répétition d'un événement. {@code onlyWith} : seulement quand les enfants sont chez ce parent (null : peu
 * importe) ; {@code count} : nombre de séances, seulement pour la fin COUNT. Personnalisée ({@code CUSTOM}) : tous
 * les {@code interval} {@code unit} ; par semaine, les {@code days} choisis (dont le jour de l'événement) ; par
 * mois, {@code monthMode}.
 */
public record RepeatRequest(
        @NotNull RepeatFrequency frequency,
        @Min(1) @Max(12) Integer interval,
        RepeatUnit unit,
        RepeatMonthMode monthMode,
        @Valid List<@NotNull RepeatDay> days,
        FamilyRole onlyWith,
        boolean skipHolidays,
        @NotNull RepeatEnd end,
        @Min(1) @Max(99) Integer count
) {
}
