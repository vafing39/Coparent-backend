package com.vynk.coparent_backend.document.service;

import com.vynk.coparent_backend.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Jetons des liens temporaires vers un document : l'id du document et l'expiration, signés (HMAC-SHA256 avec une
 * clé dérivée du secret JWT). Rien n'est stocké ; un jeton modifié ou expiré est refusé.
 */
@Component
@RequiredArgsConstructor
public class DocumentLinks {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int PAYLOAD_BYTES = 16 + 8;

    private final AuthProperties properties;

    public String token(UUID documentId, Instant expiresAt) {
        byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                .putLong(documentId.getMostSignificantBits())
                .putLong(documentId.getLeastSignificantBits())
                .putLong(expiresAt.getEpochSecond())
                .array();
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    /** L'id du document si le jeton est intact et pas expiré. */
    public Optional<UUID> verify(String token, Instant now) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (payload.length != PAYLOAD_BYTES || !MessageDigest.isEqual(sign(payload), signature)) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            if (now.getEpochSecond() >= buffer.getLong()) {
                return Optional.empty();
            }
            return Optional.of(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Clé propre aux liens de documents : un jeton ne peut servir à rien d'autre. */
    private byte[] key() throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal("document-links".getBytes(StandardCharsets.UTF_8));
    }
}
