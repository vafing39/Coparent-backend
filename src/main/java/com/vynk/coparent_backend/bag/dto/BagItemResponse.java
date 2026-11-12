package com.vynk.coparent_backend.bag.dto;

import java.util.UUID;

/** {@code checkedBy} : prénom de qui a coché pour ce passage ; null si non coché ou compte supprimé. */
public record BagItemResponse(UUID id, String label, boolean checked, String checkedBy) {
}
