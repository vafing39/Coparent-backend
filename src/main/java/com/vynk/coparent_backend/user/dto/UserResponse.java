package com.vynk.coparent_backend.user.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        boolean emailVerified,
        Instant createdAt
) {
}
