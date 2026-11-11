package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.VacationZone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Étape 2 : création ou modification de la famille. */
public record FamilyRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull VacationZone vacationZone
) {
}
