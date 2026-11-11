package com.vynk.coparent_backend.family.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ChildResponse(UUID id, String firstName, LocalDate birthDate) {
}
