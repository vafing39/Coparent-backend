package com.vynk.coparent_backend.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Étape 5 : invitation de l'autre parent. */
public record InvitationRequest(@NotBlank @Size(max = 100) String inviteeFirstName) {
}
