package com.vynk.coparent_backend.auth.dto;

import com.vynk.coparent_backend.user.dto.UserResponse;

import java.time.Instant;

public record AuthResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserResponse user
) {
}
