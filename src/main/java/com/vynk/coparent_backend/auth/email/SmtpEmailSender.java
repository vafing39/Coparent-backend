package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.config.MailProperties;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends account emails through the SMTP server in {@code spring.mail.*} (Mailpit in development, an EU
 * provider in production). Sending is asynchronous and a failure never fails the request: the user can ask
 * for the link again. Failures are logged with the user id only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final AuthProperties authProperties;

    @Async
    @Override
    public void sendEmailVerification(User user, String link) {
        send(user, "Confirmez votre adresse email",
                "Bonjour " + user.getFirstName() + ",",
                "Pour confirmer votre adresse email, ouvrez le lien ci-dessous :",
                link,
                "Ce lien est valable " + describe(authProperties.emailVerificationTtl())
                        + ". Si vous n'avez pas créé de compte Coparent, ignorez cet email.");
    }

    @Async
    @Override
    public void sendPasswordReset(User user, String link) {
        send(user, "Réinitialisation de votre mot de passe",
                "Bonjour " + user.getFirstName() + ",",
                "Une réinitialisation du mot de passe de votre compte a été demandée. Pour choisir un nouveau mot de passe, ouvrez le lien ci-dessous :",
                link,
                "Ce lien est valable " + describe(authProperties.passwordResetTtl())
                        + " et ne peut servir qu'une fois. Si vous n'êtes pas à l'origine de cette demande, ignorez cet email : votre mot de passe reste inchangé.");
    }

    private void send(User user, String subject, String greeting, String intro, String link, String footer) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.from());
            helper.setTo(user.getEmail());
            helper.setSubject(subject);
            helper.setText(plainText(greeting, intro, link, footer), html(greeting, intro, link, footer));
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            log.error("[email] Could not send \"{}\" to user {}: {}", subject, user.getId(), e.getMessage());
        }
    }

    private static String plainText(String greeting, String intro, String link, String footer) {
        return greeting + "\n\n" + intro + "\n" + link + "\n\n" + footer + "\n\nL'équipe Coparent\n";
    }

    private static String html(String greeting, String intro, String link, String footer) {
        String safeLink = HtmlUtils.htmlEscape(link);
        return """
                <!doctype html>
                <html lang="fr"><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; color: #1f2937; line-height: 1.5;">
                <p>%s</p>
                <p>%s</p>
                <p><a href="%s" style="display: inline-block; padding: 10px 18px; background: #2563eb; color: #ffffff; text-decoration: none; border-radius: 6px;">Ouvrir le lien</a></p>
                <p style="font-size: 13px; color: #6b7280;">Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>%s</p>
                <p style="font-size: 13px; color: #6b7280;">%s</p>
                <p>L'équipe Coparent</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(intro), safeLink, safeLink,
                HtmlUtils.htmlEscape(footer));
    }

    /** "24 heures", "30 minutes". */
    static String describe(Duration duration) {
        if (duration.toMinutes() % 60 == 0) {
            long hours = duration.toHours();
            return hours + (hours > 1 ? " heures" : " heure");
        }
        long minutes = duration.toMinutes();
        return minutes + (minutes > 1 ? " minutes" : " minute");
    }
}
