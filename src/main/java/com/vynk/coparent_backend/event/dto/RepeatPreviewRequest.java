package com.vynk.coparent_backend.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Aperçu d'une répétition avant l'enregistrement : première séance et règle. */
public record RepeatPreviewRequest(
        @NotNull LocalDate date,
        @NotNull @Valid RepeatRequest repeat
) {
}
