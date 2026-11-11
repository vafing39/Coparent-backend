package com.vynk.coparent_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of /refresh and /logout. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
