package com.vynk.coparent_backend.exchange.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Nouvelle demande : je prends les nuits {@code takeFrom..takeTo} (chez l'autre parent) et, en option, je lui
 * laisse {@code giveFrom..giveTo} (chez moi). Dates de nuits, heure de Paris, bornes incluses.
 */
public record ExchangeRequest(
        @NotNull LocalDate takeFrom,
        @NotNull LocalDate takeTo,
        LocalDate giveFrom,
        LocalDate giveTo,
        @Size(max = 500) String message
) {
}
