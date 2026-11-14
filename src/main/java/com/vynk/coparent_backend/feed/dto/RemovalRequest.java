package com.vynk.coparent_backend.feed.dto;

import com.vynk.coparent_backend.feed.entity.RemovalReason;

/** Demande de retrait ; raison facultative. */
public record RemovalRequest(RemovalReason reason) {
}
