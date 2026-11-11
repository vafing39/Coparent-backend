package com.vynk.coparent_backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Settings under {@code app.auth} in application.yaml. */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration emailVerificationTtl,
        @NotNull Duration passwordResetTtl,
        boolean checkCompromisedPasswords,
        @NotBlank String publicBaseUrl
) {
}
