package com.vynk.coparent_backend.account.dto;

import jakarta.validation.constraints.NotBlank;

/** Current password (checked again), then the new one twice. */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword,
        @NotBlank String confirmPassword
) {
}
