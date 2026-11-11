package com.vynk.coparent_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@NotBlank String email) {
}
