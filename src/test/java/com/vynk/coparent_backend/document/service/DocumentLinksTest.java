package com.vynk.coparent_backend.document.service;

import com.vynk.coparent_backend.config.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentLinksTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private final DocumentLinks links = new DocumentLinks(properties("test-only-secret-that-is-at-least-32-bytes-long"));

    @Test
    void aTokenOpensItsDocumentUntilItExpires() {
        UUID id = UUID.randomUUID();
        String token = links.token(id, NOW.plus(Duration.ofMinutes(5)));

        assertThat(links.verify(token, NOW)).contains(id);
        assertThat(links.verify(token, NOW.plus(Duration.ofMinutes(4)))).contains(id);
        assertThat(links.verify(token, NOW.plus(Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void aModifiedOrForeignTokenIsRefused() {
        UUID id = UUID.randomUUID();
        String token = links.token(id, NOW.plus(Duration.ofMinutes(5)));
        String other = links.token(UUID.randomUUID(), NOW.plus(Duration.ofDays(30)));

        // charge utile d'un autre jeton (plus long), signature de celui-ci
        String swapped = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThat(links.verify(swapped, NOW)).isEmpty();
        assertThat(links.verify(token.substring(0, token.length() - 2) + "AA", NOW)).isEmpty();
        assertThat(links.verify("pas-un-jeton", NOW)).isEmpty();
        assertThat(links.verify("@@@.@@@", NOW)).isEmpty();
        // signé avec un autre secret
        DocumentLinks elsewhere = new DocumentLinks(properties("another-secret-that-is-also-32-bytes-long!!"));
        assertThat(elsewhere.verify(token, NOW)).isEmpty();
    }

    private static AuthProperties properties(String secret) {
        return new AuthProperties(secret, Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(15),
                Duration.ofMinutes(15), Duration.ofDays(7), false, "http://localhost:8080");
    }
}
