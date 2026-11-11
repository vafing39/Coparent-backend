package com.vynk.coparent_backend.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Étape 3 : un enfant. */
public record ChildRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotNull @PastOrPresent LocalDate birthDate
) {
}
