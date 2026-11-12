package com.vynk.coparent_backend.bag.dto;

import java.util.List;
import java.util.UUID;

/** Les éléments du sac d'un enfant, dans l'ordre d'ajout. */
public record BagChildResponse(UUID childId, String firstName, List<BagItemResponse> items) {
}
