package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Creates access tokens (JWT) and opaque random tokens, and hashes opaque tokens for storage. */
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final int OPAQUE_TOKEN_BYTES = 32;
    private static final int VERIFICATION_CODE_DIGITS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public record AccessToken(String value, Instant expiresAt) {
    }

    public AccessToken issueAccessToken(User user, Instant now) {
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /** 256 bits of randomness, URL-safe (used in links and request bodies). */
    public String generateOpaqueToken() {
        byte[] bytes = new byte[OPAQUE_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 8 random digits, zero-padded (typed by the user, so digits only): email verification and password reset. */
    public String generateVerificationCode() {
        int bound = (int) Math.pow(10, VERIFICATION_CODE_DIGITS);
        return String.format("%0" + VERIFICATION_CODE_DIGITS + "d", RANDOM.nextInt(bound));
    }

    /**
     * HMAC-SHA256 hex of {@code userId:code}, keyed with the JWT secret. A short code needs a keyed hash: a plain
     * SHA-256 could be reversed by trying every code if the database leaked. The user id keeps hashes unique
     * across users (two users can receive the same code).
     */
    public String hashVerificationCode(UUID userId, String code) {
        return hmac(userId + ":" + code);
    }

    /**
     * Same keyed hash for the password-reset code, with its own prefix: an email-verification code and a reset code
     * that happen to be equal never share a hash (the column is unique).
     */
    public String hashPasswordResetCode(UUID userId, String code) {
        return hmac("password-reset:" + userId + ":" + code);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** SHA-256 hex. Enough for high-entropy random tokens (unlike passwords, they need no slow hash). */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
