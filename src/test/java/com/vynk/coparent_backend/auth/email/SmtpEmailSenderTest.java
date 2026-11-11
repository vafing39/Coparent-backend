package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.config.MailProperties;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(
            mailSender,
            new MailProperties(MailProperties.Mode.SMTP, "Coparent <no-reply@coparent.local>"),
            new AuthProperties("unused", Duration.ofMinutes(15), Duration.ofDays(30),
                    Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofDays(7), false, "http://localhost:8080"));
    private final User user = new User("julie@example.com", "hash", "Julie");

    SmtpEmailSenderTest() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void verificationEmailGoesToTheUserWithTheCode() throws Exception {
        sender.sendEmailVerification(user, "48291375");

        MimeMessage message = sentMessage();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("julie@example.com");
        assertThat(message.getSubject()).isEqualTo("Votre code de vérification Coparent");
        assertThat(raw(message)).contains("4829 1375").contains("15 minutes").doesNotContain("http");
    }

    @Test
    void resetEmailMentionsThirtyMinutes() throws Exception {
        sender.sendPasswordReset(user, "http://localhost:8080/reset-password?token=xyz");

        MimeMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Réinitialisation de votre mot de passe");
        assertThat(raw(message)).contains("reset-password?token=xyz").contains("30 minutes");
    }

    @Test
    void smtpFailureDoesNotPropagate() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> sender.sendEmailVerification(user, "48291375")).doesNotThrowAnyException();
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /** Decoded text of every text/plain and text/html part. */
    private static String raw(MimeMessage message) throws Exception {
        message.saveChanges();
        StringBuilder text = new StringBuilder();
        collectText(message, text);
        return text.toString();
    }

    private static void collectText(Part part, StringBuilder text) throws Exception {
        if (part.isMimeType("text/*")) {
            text.append(part.getContent()).append('\n');
        } else if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectText(multipart.getBodyPart(i), text);
            }
        }
    }
}
