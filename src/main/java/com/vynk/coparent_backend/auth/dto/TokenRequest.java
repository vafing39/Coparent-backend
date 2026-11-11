package com.vynk.coparent_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Token taken from an email link (email verification). */
public record TokenRequest(@NotBlank String token) {
}
