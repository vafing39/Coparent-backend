package com.vynk.coparent_backend.family.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Fiche entière d'un enfant (remplace la précédente, contacts compris). Textes facultatifs : vides ou absents, ils
 * sont effacés. Téléphones : chiffres, espaces, {@code + ( ) . -}, 6 à 30 caractères.
 */
public record ChildProfileRequest(
        @Size(max = 120) String school,
        @Size(max = 60) String grade,
        @Size(max = 100) String teacher,
        @Size(max = 500) String allergies,
        @Size(max = 120) String doctor,
        @Size(max = 30) String doctorPhone,
        @Valid @Size(max = 10) List<Contact> contacts
) {

    public record Contact(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String role,
            @Size(max = 30) String phone
    ) {
    }
}
