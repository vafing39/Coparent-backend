package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Development stand-in until a real email provider is wired: writes the link to the log.
 * Logs only the user id, never the email address (no personal data in logs).
 */
@Slf4j
@Component
public class LoggingEmailSender implements EmailSender {

    @Override
    public void sendEmailVerification(User user, String link) {
        log.info("[email] Verification link for user {}: {}", user.getId(), link);
    }

    @Override
    public void sendPasswordReset(User user, String link) {
        log.info("[email] Password reset link for user {}: {}", user.getId(), link);
    }
}
