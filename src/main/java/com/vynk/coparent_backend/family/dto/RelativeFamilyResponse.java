package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.RelativeRelation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Une famille dont je suis un proche : ce qu'il faut pour afficher son fil, rien de plus. */
public record RelativeFamilyResponse(
        UUID familyId,
        String familyName,
        UUID relativeId,
        String firstName,
        RelativeRelation relation,
        Instant joinedAt,
        List<Parent> parents,
        List<Child> children
) {

    public record Parent(FamilyRole role, String firstName) {
    }

    public record Child(UUID id, String firstName) {
    }
}
