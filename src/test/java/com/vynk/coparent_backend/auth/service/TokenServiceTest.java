package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.config.AuthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService tokenService = new TokenService(null, new AuthProperties(
            "unused", null, null, null, null, null, false, "http://localhost"));

    @Test
    void opaqueTokensAreRandomAndUrlSafe() {
        String a = tokenService.generateOpaqueToken();
        String b = tokenService.generateOpaqueToken();
        assertThat(a).isNotEqualTo(b).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void hashIsStableSha256Hex() {
        assertThat(tokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
