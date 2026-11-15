package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.config.MailProperties;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
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
 * for the code or link again. Failures are logged with the user id only.
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
    public void sendEmailVerification(User user, String code) {
        sendCode(user, "Votre code de vérification Coparent",
                "Pour confirmer votre adresse email, saisissez ce code dans l'application Coparent :", code,
                "Ce code est valable " + describe(authProperties.emailVerificationTtl())
                        + ". Ne le communiquez à personne. Si vous n'avez pas créé de compte Coparent, ignorez cet email.");
    }

    @Async
    @Override
    public void sendPasswordReset(User user, String code) {
        sendCode(user, "Réinitialisation de votre mot de passe",
                "Une réinitialisation du mot de passe de votre compte a été demandée. Pour choisir un nouveau mot de "
                        + "passe, saisissez ce code dans l'application Coparent :", code,
                "Ce code est valable " + describe(authProperties.passwordResetTtl())
                        + " et ne peut servir qu'une fois. Ne le communiquez à personne. Si vous n'êtes pas à l'origine "
                        + "de cette demande, ignorez cet email : votre mot de passe reste inchangé.");
    }

    @Async
    @Override
    public void sendDataExport(User user, String fileName, byte[] json) {
        String greeting = "Bonjour " + user.getFirstName() + ",";
        String intro = "Vous avez demandé vos données personnelles depuis l'application Coparent. Elles sont dans le "
                + "fichier joint, au format JSON.";
        String footer = "Ce fichier contient des informations sur votre famille : conservez-le en lieu sûr. Si vous "
                + "n'êtes pas à l'origine de cette demande, changez votre mot de passe dans l'application.";
        String text = greeting + "\n\n" + intro + "\n\n" + footer + "\n\nL'équipe Coparent\n";
        String html = """
                <!doctype html>
                <html lang="fr"><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; color: #1f2937; line-height: 1.5;">
                <p>%s</p>
                <p>%s</p>
                <p style="font-size: 13px; color: #6b7280;">%s</p>
                <p>L'équipe Coparent</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(intro), HtmlUtils.htmlEscape(footer));
        deliver(user, "Vos données Coparent", text, html,
                helper -> helper.addAttachment(fileName, new ByteArrayResource(json), "application/json"));
    }

    @Async
    @Override
    public void sendRelativeInvitation(String email, String firstName, String parentNames, String relation,
                                       String link) {
        String greeting = "Bonjour " + firstName + ",";
        String intro = parentNames + " vous invitent à rejoindre le fil de leur famille sur l'application Coparent, en "
                + "tant que " + relation + ". Vous y verrez les photos et nouvelles des enfants qu'ils partagent "
                + "avec la famille.";
        String footer = "Ce lien est personnel, valable 14 jours et ne sert qu'une fois. Si vous ne connaissez pas "
                + "cette famille, ignorez cet email.";
        String text = greeting + "\n\n" + intro + "\n\nRejoindre la famille : " + link + "\n\n" + footer
                + "\n\nL'équipe Coparent\n";
        String html = """
                <!doctype html>
                <html lang="fr"><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; color: #1f2937; line-height: 1.5;">
                <p>%s</p>
                <p>%s</p>
                <p style="margin: 24px 0;"><a href="%s" style="display: inline-block; padding: 14px 24px; border-radius: 999px; background: #3F6B5C; color: #ffffff; font-weight: 700; text-decoration: none;">Rejoindre la famille</a></p>
                <p style="font-size: 13px; color: #6b7280;">%s</p>
                <p>L'équipe Coparent</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(intro), HtmlUtils.htmlEscape(link),
                HtmlUtils.htmlEscape(footer));
        deliver(email, "relative invitation", "Invitation au fil de la famille", text, html, helper -> {
        });
    }

    /** An email whose only content is a code to type in the app (no link). */
    private void sendCode(User user, String subject, String intro, String code, String footer) {
        String greeting = "Bonjour " + user.getFirstName() + ",";
        String shown = displayCode(code);
        String text = greeting + "\n\n" + intro + "\n\n" + shown + "\n\n" + footer + "\n\nL'équipe Coparent\n";
        String html = """
                <!doctype html>
                <html lang="fr"><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; color: #1f2937; line-height: 1.5;">
                <p>%s</p>
                <p>%s</p>
                <p style="font-size: 32px; font-weight: 700; letter-spacing: 6px; margin: 24px 0;">%s</p>
                <p style="font-size: 13px; color: #6b7280;">%s</p>
                <p>L'équipe Coparent</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(intro), HtmlUtils.htmlEscape(shown),
                HtmlUtils.htmlEscape(footer));
        deliver(user, subject, text, html, helper -> {
        });
    }

    private interface Attachments {
        void add(MimeMessageHelper helper) throws MessagingException;
    }

    private void deliver(User user, String subject, String text, String html, Attachments attachments) {
        deliver(user.getEmail(), "user " + user.getId(), subject, text, html, attachments);
    }

    /** {@code logTarget}: who the email is for in the logs, never the address. */
    private void deliver(String to, String logTarget, String subject, String text, String html,
                         Attachments attachments) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            attachments.add(helper);
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            log.error("[email] Could not send \"{}\" to {}: {}", subject, logTarget, e.getMessage());
        }
    }

    /** "48291375" → "4829 1375", easier to read and copy. */
    static String displayCode(String code) {
        int middle = code.length() / 2;
        return code.substring(0, middle) + " " + code.substring(middle);
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
