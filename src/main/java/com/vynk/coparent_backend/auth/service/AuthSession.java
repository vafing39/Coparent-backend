package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.user.entity.User;

import java.time.Instant;

/** Result of a signup, login or refresh: a new access token and refresh token for {@code user}. */
public record AuthSession(
        TokenService.AccessToken accessToken,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        User user
) {
}
