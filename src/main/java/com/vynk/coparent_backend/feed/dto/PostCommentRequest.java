package com.vynk.coparent_backend.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCommentRequest(@NotBlank @Size(max = 1000) String body) {
}
