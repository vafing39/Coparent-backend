package com.vynk.coparent_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** New password, with the 8-digit code received by email after a forgot-password request. */
public record ResetPasswordRequest(
        @NotBlank String email,
        @NotBlank @Pattern(regexp = "\\d{8}", message = "Le code contient 8 chiffres.") String code,
        @NotBlank String newPassword,
        @NotBlank String confirmPassword
) {
}
