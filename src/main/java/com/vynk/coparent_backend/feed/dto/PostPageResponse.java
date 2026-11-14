package com.vynk.coparent_backend.feed.dto;

import java.time.Instant;
import java.util.List;

/** Une page du fil ; {@code nextBefore} : à passer en {@code before} pour la suivante, null à la fin. */
public record PostPageResponse(List<PostResponse> posts, Instant nextBefore) {
}
