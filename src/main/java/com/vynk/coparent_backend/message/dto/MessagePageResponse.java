package com.vynk.coparent_backend.message.dto;

import java.util.List;

/** Une page de messages, du plus ancien au plus récent ; {@code hasMore} s'il en existe de plus anciens. */
public record MessagePageResponse(List<MessageResponse> messages, boolean hasMore) {
}
