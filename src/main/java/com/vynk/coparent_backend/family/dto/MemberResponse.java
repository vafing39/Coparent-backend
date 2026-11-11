package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.util.UUID;

public record MemberResponse(UUID userId, String firstName, FamilyRole role) {
}
