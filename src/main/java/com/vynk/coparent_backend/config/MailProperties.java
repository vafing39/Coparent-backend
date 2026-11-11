package com.vynk.coparent_backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Settings under {@code app.mail} in application.yaml. SMTP server settings are the standard {@code spring.mail.*}. */
@Validated
@ConfigurationProperties("app.mail")
public record MailProperties(@NotNull Mode mode, @NotBlank String from) {

    public enum Mode {
        /** Send through the SMTP server configured in {@code spring.mail.*}. */
        SMTP,
        /** Don't send anything; write the links to the application log. */
        LOG
    }
}
