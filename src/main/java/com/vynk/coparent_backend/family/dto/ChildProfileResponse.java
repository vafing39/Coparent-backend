package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fiche d'un enfant. Les données de santé ne passent que par cette réponse (pas par la famille, chargée partout).
 * {@code updatedAt} (et qui) : null tant que la fiche n'a jamais été remplie.
 */
public record ChildProfileResponse(
        UUID childId,
        String school,
        String grade,
        String teacher,
        String allergies,
        String doctor,
        String doctorPhone,
        List<Contact> contacts,
        FamilyRole updatedByRole,
        String updatedByFirstName,
        Instant updatedAt
) {

    public record Contact(UUID id, String name, String role, String phone) {
    }
}
