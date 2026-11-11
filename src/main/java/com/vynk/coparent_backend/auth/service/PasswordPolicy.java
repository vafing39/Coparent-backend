package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Password rules from the spec: at least 12 characters, and not known as compromised. */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** bcrypt only uses the first 72 bytes; longer passwords are rejected rather than silently truncated. */
    private static final int MAX_BYTES = 72;

    private final CompromisedPasswordChecker compromisedPasswordChecker;

    public void validate(String password) {
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw ApiException.badRequest("Le mot de passe doit contenir au moins " + MIN_LENGTH + " caractères.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("Le mot de passe est trop long.");
        }
        if (compromisedPasswordChecker.check(password).isCompromised()) {
            throw ApiException.badRequest(
                    "Ce mot de passe apparaît dans des fuites de données connues. Choisissez-en un autre.");
        }
    }
}
