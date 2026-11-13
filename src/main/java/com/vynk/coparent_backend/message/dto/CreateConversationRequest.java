package com.vynk.coparent_backend.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nouvelle discussion : son sujet et son premier message. */
public record CreateConversationRequest(
        @NotBlank(message = "Indiquez le sujet de la discussion.")
        @Size(max = 80, message = "80 caractères au maximum.")
        String subject,
        @NotBlank(message = "Écrivez un message.")
        @Size(max = 2000, message = "2 000 caractères au maximum.")
        String body) {
}
