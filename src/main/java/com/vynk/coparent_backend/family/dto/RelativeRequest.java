package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.RelativeRelation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Proposition d'un proche par un parent. */
public record RelativeRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull RelativeRelation relation
) {
}
