package com.vynk.coparent_backend.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Nouvel élément du sac d'un enfant. */
public record BagItemRequest(
        @NotNull UUID childId,
        @NotBlank @Size(max = 100) String label
) {
}
