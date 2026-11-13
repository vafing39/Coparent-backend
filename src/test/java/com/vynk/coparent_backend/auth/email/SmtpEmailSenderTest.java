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
    void resetEmailHasTheCodeAndMentionsThirtyMinutes() throws Exception {
        sender.sendPasswordReset(user, "12345678");

        MimeMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Réinitialisation de votre mot de passe");
        assertThat(raw(message)).contains("1234 5678").contains("30 minutes").doesNotContain("http");
    }

    @Test
    void dataExportIsAttachedAsJson() throws Exception {
        sender.sendDataExport(user, "coparent-mes-donnees-2026-11-13.json",
                "{\"account\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MimeMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Vos données Coparent");
        assertThat(raw(message)).contains("fichier joint").doesNotContain("http");
        message.saveChanges();
        assertThat(attachmentNames(message)).containsExactly("coparent-mes-donnees-2026-11-13.json");
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

    private static java.util.List<String> attachmentNames(Part part) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (part.getFileName() != null) {
            names.add(part.getFileName());
        } else if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                names.addAll(attachmentNames(multipart.getBodyPart(i)));
            }
        }
        return names;
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
