package com.vynk.coparent_backend.feed.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.RelativeRelation;

import java.time.Instant;
import java.util.UUID;

/** Un commentaire d'un parent ({@code role}) ou d'un proche ({@code relation}). */
public record PostCommentResponse(UUID id, FamilyRole role, RelativeRelation relation, String firstName, String body,
                                  Instant createdAt) {
}
