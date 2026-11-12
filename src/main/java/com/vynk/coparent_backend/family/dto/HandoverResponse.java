package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDateTime;

/** Passage de garde. {@code at} : heure de Paris (pas UTC), comme l'heure de bascule enregistrée. */
public record HandoverResponse(LocalDateTime at, FamilyRole from, FamilyRole to) {
}
