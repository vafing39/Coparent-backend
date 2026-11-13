package com.vynk.coparent_backend.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "Écrivez un message.")
        @Size(max = 2000, message = "2 000 caractères au maximum.")
        String body) {
}
