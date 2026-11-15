package com.vynk.coparent_backend.feed.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Album d'un enfant pour une année : {@code years} (celles qui ont des photos, la plus récente d'abord), {@code year}
 * (l'année affichée, null sans photo) et ses photos, la plus récente d'abord.
 */
public record AlbumResponse(List<Integer> years, Integer year, List<Photo> photos) {

    /** Une photo du fil : sa publication, ses dimensions et la date de la publication. */
    public record Photo(UUID postId, UUID photoId, int width, int height, Instant createdAt) {
    }
}
