package com.vynk.coparent_backend.feed.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.feed.entity.ReactionKind;
import com.vynk.coparent_backend.feed.entity.RemovalReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Une publication du fil. {@code removal} : null sauf si l'autre parent a demandé son retrait (elle est alors
 * masquée : l'app la montre réduite, avec les actions du retrait ; plus de réactions ni de commentaires).
 * {@code canDelete} : l'auteur ; {@code canRequestRemoval} : l'autre parent, tant qu'aucun retrait n'est demandé.
 */
public record PostResponse(
        UUID id,
        FamilyRole authorRole,
        String authorFirstName,
        Instant createdAt,
        String body,
        boolean firstTime,
        List<Child> children,
        List<Photo> photos,
        List<Reaction> reactions,
        int commentCount,
        Removal removal,
        boolean canDelete,
        boolean canRequestRemoval
) {

    public record Child(UUID id, String firstName) {
    }

    public record Photo(UUID id, int width, int height) {
    }

    /** {@code mine} : j'ai déjà réagi ainsi. */
    public record Reaction(ReactionKind kind, int count, boolean mine) {
    }

    /** {@code mine} : c'est moi qui ai demandé le retrait (je peux l'annuler). */
    public record Removal(FamilyRole requestedByRole, RemovalReason reason, Instant at, boolean mine) {
    }
}
