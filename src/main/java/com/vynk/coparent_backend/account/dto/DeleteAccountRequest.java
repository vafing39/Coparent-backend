package com.vynk.coparent_backend.account.dto;

import jakarta.validation.constraints.NotBlank;

/** The password, typed again to confirm the deletion. */
public record DeleteAccountRequest(@NotBlank String password) {
}
