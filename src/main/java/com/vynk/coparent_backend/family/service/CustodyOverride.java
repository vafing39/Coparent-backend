package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;

/** Nuits {@code from}..{@code to} (incluses) attribuées à {@code parent} par un échange de garde accepté. */
public record CustodyOverride(LocalDate from, LocalDate to, FamilyRole parent) {

    public boolean contains(LocalDate night) {
        return !night.isBefore(from) && !night.isAfter(to);
    }
}
