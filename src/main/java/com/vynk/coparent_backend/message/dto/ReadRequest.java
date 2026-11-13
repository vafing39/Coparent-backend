package com.vynk.coparent_backend.message.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Lu jusqu'au message {@code upToSeq} inclus. */
public record ReadRequest(@NotNull @Positive Long upToSeq) {
}
