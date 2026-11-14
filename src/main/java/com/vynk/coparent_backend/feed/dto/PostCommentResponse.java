package com.vynk.coparent_backend.feed.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.util.UUID;

public record PostCommentResponse(UUID id, FamilyRole role, String firstName, String body, Instant createdAt) {
}
