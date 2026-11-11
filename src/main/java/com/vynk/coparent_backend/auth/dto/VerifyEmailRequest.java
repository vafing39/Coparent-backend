package com.vynk.coparent_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 8-digit code received by email (email verification). */
public record VerifyEmailRequest(
        @NotBlank @Pattern(regexp = "\\d{8}", message = "Le code contient 8 chiffres.") String code
) {
}
