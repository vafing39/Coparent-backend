package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends nothing: writes the code or link to the log. Active with {@code app.mail.mode=log}, for running without an
 * SMTP server. Logs only the user id, never the email address (no personal data in logs).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log")
public class LoggingEmailSender implements EmailSender {

    @Override
    public void sendEmailVerification(User user, String code) {
        log.info("[email] Verification code for user {}: {}", user.getId(), code);
    }

    @Override
    public void sendPasswordReset(User user, String link) {
        log.info("[email] Password reset link for user {}: {}", user.getId(), link);
    }
}
